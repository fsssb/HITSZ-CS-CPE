package cn.edu.hitsz.compiler.lexer;

import cn.edu.hitsz.compiler.symtab.SymbolTable;
import cn.edu.hitsz.compiler.utils.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * 实验一: 实现词法分析
 */
public class LexicalAnalyzer {
    private final SymbolTable symbolTable;
    // 存放读入的源代码
    private String sourceCode = "";
    // 存放识别出来的 Token 列表
    private final List<Token> tokens = new ArrayList<>();

    public LexicalAnalyzer(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

    /**
     * 从给予的路径中读取并加载文件内容
     */
    public void loadFile(String path) {
        try {
            // 完整读入源代码文件
            this.sourceCode = Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new RuntimeException("无法读取源代码文件: " + path, e);
        }
    }

    /**
     * 执行词法分析, 准备好用于返回的 token 列表
     */
    public void run() {
        int cursor = 0;
        int length = sourceCode.length();

        while (cursor < length) {
            char ch = sourceCode.charAt(cursor);

            // 1. 跳过空白符 (空格, 制表符, 换行, 回车)
            if (Character.isWhitespace(ch)) {
                cursor++;
                continue;
            }

            // 2. 识别标识符 (id) 或 关键字 (int, return)
            if (Character.isLetter(ch) || ch == '_') {
                StringBuilder sb = new StringBuilder();
                while (cursor < length && (Character.isLetterOrDigit(sourceCode.charAt(cursor)) || sourceCode.charAt(cursor) == '_')) {
                    sb.append(sourceCode.charAt(cursor));
                    cursor++;
                }
                String word = sb.toString();

                // 判断是关键字还是普通标识符
                if (word.equals("int")) {
                    tokens.add(Token.simple("int"));
                } else if (word.equals("return")) {
                    tokens.add(Token.simple("return"));
                } else {
                    // 是标识符 id
                    tokens.add(Token.normal("id", word));
                    // 维护符号表: 若不存在则添加
                    if (!symbolTable.has(word)) {
                        symbolTable.add(word);
                    }
                }
                continue; // 已经更新了 cursor，直接跳过本次循环末尾的 cursor++
            }

            // 3. 识别整常数 (IntConst)
            if (Character.isDigit(ch)) {
                StringBuilder sb = new StringBuilder();
                while (cursor < length && Character.isDigit(sourceCode.charAt(cursor))) {
                    sb.append(sourceCode.charAt(cursor));
                    cursor++;
                }
                tokens.add(Token.normal("IntConst", sb.toString()));
                continue;
            }

            // 4. 识别运算符和界符
            switch (ch) {
                case '=': tokens.add(Token.simple("=")); break;
                case '+': tokens.add(Token.simple("+")); break;
                case '-': tokens.add(Token.simple("-")); break;
                case '*': tokens.add(Token.simple("*")); break;
                case '/': tokens.add(Token.simple("/")); break;
                case '(': tokens.add(Token.simple("(")); break;
                case ')': tokens.add(Token.simple(")")); break;
                case ';': tokens.add(Token.simple("Semicolon")); break;
                case ',': tokens.add(Token.simple(",")); break;
                default:
                    // 遇到非法字符可以报错或者忽略
                    System.err.println("警告: 忽略非法字符 '" + ch + "'");
                    break;
            }
            cursor++;
        }

        // 5. 词法分析结束，必须添加 EOF 标记 ($ , )
        tokens.add(Token.eof());
    }

    /**
     * 获得词法分析的结果
     */
    public Iterable<Token> getTokens() {
        return tokens;
    }

    public void dumpTokens(String path) {
        FileUtils.writeLines(
                path,
                StreamSupport.stream(getTokens().spliterator(), false).map(Token::toString).toList()
        );
    }
}
