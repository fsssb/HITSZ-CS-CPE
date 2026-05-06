package cn.edu.hitsz.compiler.parser;

import cn.edu.hitsz.compiler.lexer.Token;
import cn.edu.hitsz.compiler.parser.table.Production;
import cn.edu.hitsz.compiler.parser.table.Status;
import cn.edu.hitsz.compiler.symtab.SourceCodeType;
import cn.edu.hitsz.compiler.symtab.SymbolTable;

import java.util.Stack;

public class SemanticAnalyzer implements ActionObserver {

    private SymbolTable symbolTable;
    private final Stack<Object> semStack = new Stack<>();

    @Override
    public void whenShift(Status currentStatus, Token currentToken) {
        semStack.push(currentToken);
    }

    @Override
    public void whenReduce(Status currentStatus, Production production) {
        int bodySize = production.body().size();
        int pid = production.index();

        switch (pid) {
            case 5: { // D -> int
                popN(bodySize);
                semStack.push(SourceCodeType.Int);
                break;
            }
            case 4: { // S -> D id
                Token idToken = (Token) semStack.pop();  // id
                SourceCodeType type = (SourceCodeType) semStack.pop(); // D
                var entry = symbolTable.get(idToken.getText());
                if (entry == null) {
                    throw new RuntimeException("Semantic error: variable '" + idToken.getText() + "' not found in symbol table");
                }
                entry.setType(type);
                semStack.push(null);
                break;
            }
            case 14: { // B -> id
                Token idToken = (Token) semStack.pop();
                var entry = symbolTable.get(idToken.getText());
                if (entry == null || entry.getType() == null) {
                    throw new RuntimeException("Semantic error: variable '" + idToken.getText() + "' used before declaration");
                }
                semStack.push(SourceCodeType.Int);
                break;
            }
            case 6: { // S -> id = E
                SourceCodeType eType = popSemType(semStack);  // E
                semStack.pop();  // =
                Token idToken = (Token) semStack.pop(); // id
                var entry = symbolTable.get(idToken.getText());
                if (entry == null || entry.getType() == null) {
                    throw new RuntimeException("Semantic error: variable '" + idToken.getText() + "' used before declaration");
                }
                semStack.push(null);
                break;
            }
            case 15: { // B -> IntConst
                popN(bodySize);
                semStack.push(SourceCodeType.Int);
                break;
            }
            case 7: { // S -> return E
                popN(bodySize);
                semStack.push(null);
                break;
            }
            case 1: // P -> S_list
            case 2: // S_list -> S Semicolon S_list
            case 3: { // S_list -> S Semicolon
                popN(bodySize);
                semStack.push(null);
                break;
            }
            case 8:  // E -> E + A
            case 9:  // E -> E - A
            case 10: // E -> A
            case 11: // A -> A * B
            case 12: { // A -> B
                popN(bodySize);
                semStack.push(SourceCodeType.Int);
                break;
            }
            case 13: { // B -> ( E )
                // pop ), E, (
                popN(bodySize);
                semStack.push(SourceCodeType.Int);
                break;
            }
            default:
                throw new RuntimeException("SemanticAnalyzer: unknown production index " + pid);
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

    private void popN(int n) {
        for (int i = 0; i < n; i++) {
            semStack.pop();
        }
    }

    private SourceCodeType popSemType(Stack<Object> stack) {
        Object obj = stack.pop();
        if (obj instanceof SourceCodeType) {
            return (SourceCodeType) obj;
        }
        return null;
    }
}
