package cn.edu.hitsz.compiler.parser;

import cn.edu.hitsz.compiler.ir.IRImmediate;
import cn.edu.hitsz.compiler.ir.IRValue;
import cn.edu.hitsz.compiler.ir.IRVariable;
import cn.edu.hitsz.compiler.ir.Instruction;
import cn.edu.hitsz.compiler.lexer.Token;
import cn.edu.hitsz.compiler.parser.table.Production;
import cn.edu.hitsz.compiler.parser.table.Status;
import cn.edu.hitsz.compiler.symtab.SymbolTable;
import cn.edu.hitsz.compiler.utils.FileUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class IRGenerator implements ActionObserver {

    private SymbolTable symbolTable;
    private final List<Instruction> instructions = new ArrayList<>();
    private final Stack<Object> irStack = new Stack<>();

    @Override
    public void whenShift(Status currentStatus, Token currentToken) {
        irStack.push(currentToken);
    }

    @Override
    public void whenReduce(Status currentStatus, Production production) {
        int bodySize = production.body().size();
        int pid = production.index();

        switch (pid) {
            case 15: { // B -> IntConst
                Token intConst = (Token) popN(bodySize).get(0);
                int value = Integer.parseInt(intConst.getText());
                irStack.push(IRImmediate.of(value));
                break;
            }
            case 14: { // B -> id
                Token idToken = (Token) popN(bodySize).get(0);
                irStack.push(IRVariable.named(idToken.getText()));
                break;
            }
            case 12: { // A -> B
                IRValue b = popIRValue();
                irStack.push(b);
                break;
            }
            case 10: { // E -> A
                IRValue a = popIRValue();
                irStack.push(a);
                break;
            }
            case 11: { // A -> A * B
                IRValue bMul = popIRValue();
                popToken(); // *
                IRValue aMul = popIRValue();
                IRVariable temp = IRVariable.temp();
                instructions.add(Instruction.createMul(temp, aMul, bMul));
                irStack.push(temp);
                break;
            }
            case 8: { // E -> E + A
                IRValue aAdd = popIRValue();
                popToken(); // +
                IRValue eAdd = popIRValue();
                IRVariable temp = IRVariable.temp();
                instructions.add(Instruction.createAdd(temp, eAdd, aAdd));
                irStack.push(temp);
                break;
            }
            case 9: { // E -> E - A
                IRValue aSub = popIRValue();
                popToken(); // -
                IRValue eSub = popIRValue();
                IRVariable temp = IRVariable.temp();
                instructions.add(Instruction.createSub(temp, eSub, aSub));
                irStack.push(temp);
                break;
            }
            case 6: { // S -> id = E
                IRValue eVal = popIRValue();
                popToken(); // =
                Token idToken = (Token) irStack.pop();
                instructions.add(Instruction.createMov(IRVariable.named(idToken.getText()), eVal));
                irStack.push(null);
                break;
            }
            case 7: { // S -> return E
                IRValue retVal = popIRValue();
                popToken(); // return
                instructions.add(Instruction.createRet(retVal));
                irStack.push(null);
                break;
            }
            case 13: { // B -> ( E )
                popToken(); // )
                IRValue eParen = popIRValue();
                popToken(); // (
                irStack.push(eParen);
                break;
            }
            // Pass-through productions (no IR value produced)
            case 1:  // P -> S_list
            case 2:  // S_list -> S Semicolon S_list
            case 3:  // S_list -> S Semicolon
            case 4:  // S -> D id
            case 5:  // D -> int
                popN(bodySize);
                irStack.push(null);
                break;
            default:
                throw new RuntimeException("IRGenerator: unknown production index " + pid);
        }
    }

    @Override
    public void whenAccept(Status currentStatus) {
        // nothing to do
    }

    @Override
    public void setSymbolTable(SymbolTable table) {
        this.symbolTable = table;
    }

    public List<Instruction> getIR() {
        return instructions;
    }

    public void dumpIR(String path) {
        FileUtils.writeLines(path, getIR().stream().map(Instruction::toString).toList());
    }

    private List<Object> popN(int n) {
        List<Object> popped = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            popped.add(0, irStack.pop());
        }
        return popped;
    }

    private IRValue popIRValue() {
        Object obj = irStack.pop();
        if (obj instanceof IRValue) {
            return (IRValue) obj;
        }
        throw new RuntimeException("IRGenerator: expected IRValue on stack but got " + obj);
    }

    private Token popToken() {
        Object obj = irStack.pop();
        if (obj instanceof Token) {
            return (Token) obj;
        }
        throw new RuntimeException("IRGenerator: expected Token on stack but got " + obj);
    }
}
