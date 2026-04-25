package cn.edu.hitsz.compiler.parser;

import cn.edu.hitsz.compiler.lexer.Token;
import cn.edu.hitsz.compiler.parser.table.*;
import cn.edu.hitsz.compiler.symtab.SymbolTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * 实验二: 实现 LR 语法分析驱动程序
 */
public class SyntaxAnalyzer {
    private final SymbolTable symbolTable;
    private final List<ActionObserver> observers = new ArrayList<>();
    
    // 私有成员变量：存储词法单元和分析表
    private final List<Token> tokenList = new ArrayList<>();
    private LRTable lrTable;

    public SyntaxAnalyzer(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

    /**
     * 辅助类：包装栈中的符号
     * 按照指导书第 55 页实现
     */
    private static class Symbol {
        private Token token;
        private NonTerminal nonTerminal;

        public Symbol(Token token) { this.token = token; this.nonTerminal = null; }
        public Symbol(NonTerminal nonTerminal) { this.token = null; this.nonTerminal = nonTerminal; }

        public boolean isToken() { return token != null; }
        public Token getToken() { return token; }
        public NonTerminal getNonTerminal() { return nonTerminal; }
    }

    public void registerObserver(ActionObserver observer) {
        observers.add(observer);
        observer.setSymbolTable(symbolTable);
    }

    public void callWhenInShift(Status currentStatus, Token currentToken) {
        for (final var listener : observers) {
            listener.whenShift(currentStatus, currentToken);
        }
    }

    public void callWhenInReduce(Status currentStatus, Production production) {
        for (final var listener : observers) {
            listener.whenReduce(currentStatus, production);
        }
    }

    public void callWhenInAccept(Status currentStatus) {
        for (final var listener : observers) {
            listener.whenAccept(currentStatus);
        }
    }

    public void loadTokens(Iterable<Token> tokens) {
        // 将 Iterable 转存为 List，方便按索引访问（不消耗 Token）
        for (Token token : tokens) {
            tokenList.add(token);
        }
    }

    public void loadLRTable(LRTable table) {
        this.lrTable = table;
    }

    public void run() {
        // 1. 初始化状态栈和符号栈
        Stack<Status> statusStack = new Stack<>();
        Stack<Symbol> symbolStack = new Stack<>();

        // 压入初始状态（从分析表获取）
        statusStack.push(lrTable.getInit());

        int cursor = 0;
        while (cursor < tokenList.size()) {
            Status currentStatus = statusStack.peek();
            Token currentToken = tokenList.get(cursor);

            // 2. 查询 Action 表
            Action action = lrTable.getAction(currentStatus, currentToken);

            switch (action.getKind()) {
                case Shift -> {
                    // 【移进动作】
                    // 调用观察者
                    callWhenInShift(currentStatus, currentToken);
                    // 状态入栈
                    statusStack.push(action.getStatus());
                    // 符号入栈
                    symbolStack.push(new Symbol(currentToken));
                    // 消耗当前 Token，光标后移
                    cursor++;
                }

                case Reduce -> {
                    // 【归约动作】
                    Production production = action.getProduction();
                    // 调用观察者
                    callWhenInReduce(currentStatus, production);
                    
                    // 按照产生式右部长度，同步弹出两个栈
                    int bodySize = production.body().size();
                    for (int i = 0; i < bodySize; i++) {
                        statusStack.pop();
                        symbolStack.pop();
                    }

                    // 查 GOTO 表：根据弹栈后的栈顶状态和产生式左部(Head)确定新状态
                    Status topStatus = statusStack.peek();
                    NonTerminal head = production.head();
                    Status nextStatus = topStatus.getGoto(head);

                    // 新状态和非终结符入栈
                    statusStack.push(nextStatus);
                    symbolStack.push(new Symbol(head));
                    // 注意：Reduce 动作不消耗 Token，光标 cursor 不移动
                }

                case Accept -> {
                    // 【接受动作】
                    callWhenInAccept(currentStatus);
                    return; // 正常结束
                }

                case Error -> {
                    // 【错误动作】
                    throw new RuntimeException("Syntax Error: Unexpected token " + currentToken.getText()
                            + " at status " + currentStatus.index());
                }
            }
        }
    }
}