package cn.edu.hitsz.compiler.asm;

import cn.edu.hitsz.compiler.ir.IRImmediate;
import cn.edu.hitsz.compiler.ir.IRValue;
import cn.edu.hitsz.compiler.ir.IRVariable;
import cn.edu.hitsz.compiler.ir.Instruction;
import cn.edu.hitsz.compiler.ir.InstructionKind;
import cn.edu.hitsz.compiler.utils.FileUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AssemblyGenerator {

    private List<Instruction> normalizedIR;
    private Map<String, Integer> lastUsage;
    private final List<String> asmLines = new ArrayList<>();

    // 寄存器描述表
    private final Map<String, String> regToVar = new HashMap<>();  // t0 → "a"
    private final Map<String, String> varToReg = new HashMap<>();  // "a" → t0
    private final Map<String, Integer> spillOffsets = new HashMap<>();
    private int nextSpillOffset = 0;

    private static final String[] REG_POOL = {"t0", "t1", "t2", "t3", "t4", "t5", "t6"};

    //============================== loadIR ==============================

    public void loadIR(List<Instruction> originInstructions) {
        // 阶段1：规范化 IR
        normalizedIR = new ArrayList<>();
        for (Instruction inst : originInstructions) {
            normalize(inst);
        }

        // 阶段2：统计每个变量的 lastUsage
        lastUsage = new HashMap<>();
        for (int i = 0; i < normalizedIR.size(); i++) {
            Instruction inst = normalizedIR.get(i);
            for (String varName : collectVarNames(inst)) {
                lastUsage.put(varName, i);
            }
        }
    }

    /**
     * 规范化单条指令，可能产生多条指令追加到 normalizedIR
     */
    private void normalize(Instruction inst) {
        if (!inst.getKind().isBinary()) {
            normalizedIR.add(inst);
            return;
        }

        IRValue lhs = inst.getLHS();
        IRValue rhs = inst.getRHS();
        InstructionKind kind = inst.getKind();

        // 双立即数：常量折叠
        if (lhs.isImmediate() && rhs.isImmediate()) {
            int lv = ((IRImmediate) lhs).getValue();
            int rv = ((IRImmediate) rhs).getValue();
            int result = switch (kind) {
                case ADD -> lv + rv;
                case SUB -> lv - rv;
                case MUL -> lv * rv;
                default -> throw new IllegalStateException("Unexpected binary kind: " + kind);
            };
            normalizedIR.add(Instruction.createMov(inst.getResult(), IRImmediate.of(result)));
            return;
        }

        // ADD：左立即数 → 交换
        if (kind == InstructionKind.ADD && lhs.isImmediate()) {
            normalizedIR.add(Instruction.createAdd(inst.getResult(), rhs, lhs));
            return;
        }

        // SUB/MUL：左立即数 → 先 MOV temp, imm
        if ((kind == InstructionKind.SUB || kind == InstructionKind.MUL) && lhs.isImmediate()) {
            IRVariable temp = IRVariable.temp();
            normalizedIR.add(Instruction.createMov(temp, lhs));
            if (kind == InstructionKind.SUB) {
                normalizedIR.add(Instruction.createSub(inst.getResult(), temp, rhs));
            } else {
                normalizedIR.add(Instruction.createMul(inst.getResult(), temp, rhs));
            }
            return;
        }

        // MUL：右立即数 → 先 MOV temp, imm
        if (kind == InstructionKind.MUL && rhs.isImmediate()) {
            IRVariable temp = IRVariable.temp();
            normalizedIR.add(Instruction.createMov(temp, rhs));
            normalizedIR.add(Instruction.createMul(inst.getResult(), lhs, temp));
            return;
        }

        normalizedIR.add(inst);
    }

    /**
     * 收集指令中出现的所有 IRVariable 名称
     */
    private List<String> collectVarNames(Instruction inst) {
        List<String> names = new ArrayList<>();
        switch (inst.getKind()) {
            case ADD, SUB, MUL -> {
                if (inst.getResult() != null) names.add(inst.getResult().getName());
                addIfVar(names, inst.getLHS());
                addIfVar(names, inst.getRHS());
            }
            case MOV -> {
                if (inst.getResult() != null) names.add(inst.getResult().getName());
                addIfVar(names, inst.getFrom());
            }
            case RET -> addIfVar(names, inst.getReturnValue());
            case BZ -> {
                addIfVar(names, inst.getBranchCondition());
                // label is stored as IRVariable but not a real variable
            }
            case LABEL -> {
                if (inst.getResult() != null) names.add(inst.getResult().getName());
            }
            // JMP: label only, no variable refs
        }
        return names;
    }

    private static void addIfVar(List<String> names, IRValue val) {
        if (val instanceof IRVariable v) {
            names.add(v.getName());
        }
    }

    //============================== run ==============================

    public void run() {
        asmLines.clear();
        regToVar.clear();
        varToReg.clear();
        spillOffsets.clear();
        nextSpillOffset = 0;
        for (String reg : REG_POOL) {
            regToVar.put(reg, null);
        }

        asmLines.add(".text");

        for (int ip = 0; ip < normalizedIR.size(); ip++) {
            Instruction inst = normalizedIR.get(ip);
            if (inst.getKind().isUnary()) {
                genUnary(inst, ip);
            } else if (inst.getKind().isBinary()) {
                genBinary(inst, ip);
            } else if (inst.getKind().isReturn()) {
                genReturn(inst, ip);
            } else if (inst.getKind().isBranch()) {
                genBranch(inst, ip);
            } else if (inst.getKind().isJump()) {
                genJump(inst, ip);
            } else if (inst.getKind().isLabel()) {
                asmLabel(inst);
            }
        }
    }

    //==================== 寄存器分配 ====================

    /**
     * 为变量分配寄存器，reserved 中的寄存器不可被抢占（正在被当前指令读取）
     */
    private String allocateReg(String varName, int ip, Set<String> reserved) {
        // 第1级：找空闲寄存器
        for (String reg : REG_POOL) {
            if (regToVar.get(reg) == null) {
                assignReg(reg, varName);
                return reg;
            }
        }

        // 第2级：抢占已死亡变量的寄存器（但不抢 reserved）
        for (String reg : REG_POOL) {
            if (reserved.contains(reg)) continue;
            String occupiedVar = regToVar.get(reg);
            if (occupiedVar != null && lastUsage.getOrDefault(occupiedVar, -1) <= ip) {
                freeReg(reg);
                assignReg(reg, varName);
                return reg;
            }
        }

        // 第3级：spill 一个非 reserved 寄存器
        for (String reg : REG_POOL) {
            if (reserved.contains(reg)) continue;
            String oldVar = regToVar.get(reg);
            if (oldVar == null) continue;
            nextSpillOffset -= 4;
            spillOffsets.put(oldVar, nextSpillOffset);
            varToReg.remove(oldVar);
            emit("sw %s, %d(sp)".formatted(reg, nextSpillOffset), null);
            regToVar.put(reg, varName);
            varToReg.put(varName, reg);
            return reg;
        }

        throw new RuntimeException("All registers reserved or in use, cannot allocate");
    }

    private void assignReg(String reg, String varName) {
        regToVar.put(reg, varName);
        varToReg.put(varName, reg);
    }

    private void freeReg(String reg) {
        String var = regToVar.get(reg);
        if (var != null) {
            varToReg.remove(var);
            regToVar.put(reg, null);
        }
    }

    /**
     * 确保变量值在寄存器中（用于读取操作数），返回所在寄存器名
     */
    private String ensureReg(String varName, int ip, Set<String> reserved) {
        if (varToReg.containsKey(varName)) {
            return varToReg.get(varName);
        }
        if (spillOffsets.containsKey(varName)) {
            String reg = allocateReg(varName, ip, reserved);
            int offset = spillOffsets.remove(varName);
            emit("lw %s, %d(sp)".formatted(reg, offset), null);
            return reg;
        }
        throw new RuntimeException("Variable " + varName + " not in register or spill");
    }

    private void freeDeadRegs(int ip) {
        for (String reg : REG_POOL) {
            String var = regToVar.get(reg);
            if (var != null && lastUsage.getOrDefault(var, -1) <= ip) {
                freeReg(reg);
            }
        }
    }

    //==================== 指令生成 ====================

    private void genUnary(Instruction inst, int ip) {
        String resultName = inst.getResult().getName();
        IRValue from = inst.getFrom();

        if (from.isImmediate()) {
            int imm = ((IRImmediate) from).getValue();
            String rd = allocateReg(resultName, ip, Set.of());
            emit("li %s, %d".formatted(rd, imm), inst);
        } else {
            String varName = ((IRVariable) from).getName();
            Set<String> reserved = new HashSet<>();
            String rs = ensureReg(varName, ip, reserved);
            reserved.add(rs);
            String rd = allocateReg(resultName, ip, reserved);
            emit("mv %s, %s".formatted(rd, rs), inst);
        }
        freeDeadRegs(ip);
    }

    private void genBinary(Instruction inst, int ip) {
        String resultName = inst.getResult().getName();
        IRValue lhs = inst.getLHS();
        IRValue rhs = inst.getRHS();

        Set<String> reserved = new HashSet<>();
        String rs1 = ensureReg(((IRVariable) lhs).getName(), ip, reserved);
        reserved.add(rs1);

        if (inst.getKind() == InstructionKind.ADD && rhs.isImmediate()) {
            String rd = allocateReg(resultName, ip, reserved);
            int imm = ((IRImmediate) rhs).getValue();
            emit("addi %s, %s, %d".formatted(rd, rs1, imm), inst);
        } else {
            String rs2 = ensureReg(((IRVariable) rhs).getName(), ip, reserved);
            reserved.add(rs2);
            String rd = allocateReg(resultName, ip, reserved);
            String op = switch (inst.getKind()) {
                case ADD -> "add";
                case SUB -> "sub";
                case MUL -> "mul";
                default -> throw new IllegalStateException("Unexpected binary kind: " + inst.getKind());
            };
            emit("%s %s, %s, %s".formatted(op, rd, rs1, rs2), inst);
        }
        freeDeadRegs(ip);
    }

    private void genReturn(Instruction inst, int ip) {
        IRValue val = inst.getReturnValue();
        String rs = ensureReg(((IRVariable) val).getName(), ip, Set.of());
        emit("mv a0, %s".formatted(rs), inst);
    }

    private void genBranch(Instruction inst, int ip) {
        IRValue cond = inst.getBranchCondition();
        String rs = ensureReg(((IRVariable) cond).getName(), ip, Set.of());
        String label = inst.getBranchLabel();
        emit("beq %s, x0, %s".formatted(rs, label), inst);
        freeDeadRegs(ip);
    }

    private void genJump(Instruction inst, int ip) {
        String label = inst.getBranchLabel();
        emit("j %s".formatted(label), inst);
    }

    private void asmLabel(Instruction inst) {
        String name = inst.getLabelName();
        asmLines.add(name + ":");
    }

    //==================== 工具方法 ====================

    private void emit(String asm, Instruction ir) {
        if (ir != null) {
            asmLines.add("    " + asm + "\t\t#  " + ir);
        } else {
            asmLines.add("    " + asm);
        }
    }

    //============================== dump ==============================

    public void dump(String path) {
        FileUtils.writeLines(path, asmLines);
    }
}
