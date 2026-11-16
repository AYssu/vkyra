package com.vkyra.code.editor;

import com.vkyra.code.lsp.ClangdClient;

import com.vkyra.code.App;
import com.vkyra.code.Log;
import com.vkyra.code.plugin.PluginHookManager;
import com.vkyra.code.plugin.PluginManager;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.NonNull;

import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CppLanguage extends com.vkyra.code.editor.language.TextMateLanguageAdapter {

    private Context context;
    
    // Clangd 客户端（用于智能补全）
    private volatile ClangdClient clangdClient;
    private volatile String currentFilePath;
    
    public CppLanguage() {
        this(App.getInstance());
    }

    public CppLanguage(Context context) {
        super(prepareGrammar(context), false);
        this.context = context == null ? null : context.getApplicationContext();
    }

    private static String prepareGrammar(Context context) {
        com.vkyra.code.editor.language.EditorLanguages.initialize(context);
        return "source.cpp";
    }
    
    /**
     * 设置 Clangd 客户端
     */
    public void setClangdClient(ClangdClient client) {
        this.clangdClient = client;
    }
    
    /**
     * 设置当前文件路径（用于 clangd 补全）
     */
    public void setCurrentFilePath(String filePath) {
        this.currentFilePath = filePath;
    }
    
    /**
     * 检查 clangd 是否可用
     */
    public boolean isClangdAvailable() {
        boolean available = clangdClient != null && clangdClient.isInitialized() && currentFilePath != null;
        if (!available) {
            android.util.Log.d("CppLanguage", "isClangdAvailable: false (client=" + (clangdClient != null) 
                + ", initialized=" + (clangdClient != null ? clangdClient.isInitialized() : "N/A")
                + ", filePath=" + (currentFilePath != null));
        }
        return available;
    }

    // C++ 关键字
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor",
        "break", "case", "catch", "class", "compl", "concept", "const", "consteval",
        "constexpr", "constinit", "const_cast", "continue", "co_await", "co_return",
        "co_yield", "decltype", "default", "delete", "do", "dynamic_cast", "else",
        "enum", "explicit", "export", "extern", "false", "for", "friend", "goto",
        "if", "inline", "mutable", "namespace", "new", "noexcept", "not", "not_eq",
        "nullptr", "operator", "or", "or_eq", "private", "protected", "public",
        "register", "reinterpret_cast", "requires", "return", "sizeof", "static",
        "static_assert", "static_cast", "struct", "switch", "template", "this",
        "thread_local", "throw", "true", "try", "typedef", "typeid", "typename",
        "union", "using", "virtual", "volatile", "while", "xor", "xor_eq",
        "override", "final"
    ));

    // C++ 基本类型
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "bool", "char", "char8_t", "char16_t", "char32_t", "wchar_t",
        "short", "int", "long", "signed", "unsigned", "float", "double", "void",
        "int8_t", "int16_t", "int32_t", "int64_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "size_t", "ptrdiff_t", "intptr_t", "uintptr_t"
    ));

    // STL 容器和类型
    private static final Set<String> STL_TYPES = new HashSet<>(Arrays.asList(
        "string", "wstring", "string_view",
        "vector", "array", "deque", "list", "forward_list",
        "set", "multiset", "unordered_set", "unordered_multiset",
        "map", "multimap", "unordered_map", "unordered_multimap",
        "stack", "queue", "priority_queue",
        "pair", "tuple", "optional", "variant", "any",
        "unique_ptr", "shared_ptr", "weak_ptr",
        "function", "bind", "reference_wrapper",
        "thread", "mutex", "lock_guard", "unique_lock", "condition_variable",
        "atomic", "future", "promise", "packaged_task",
        "regex", "smatch", "cmatch",
        "fstream", "ifstream", "ofstream", "stringstream", "istringstream", "ostringstream",
        "iostream", "istream", "ostream", "cin", "cout", "cerr", "clog", "endl",
        // I/O 操纵符
        "hex", "dec", "oct", "fixed", "scientific", "left", "right", "internal",
        "setw", "setprecision", "setfill", "setbase", "boolalpha", "noboolalpha",
        "showbase", "noshowbase", "showpoint", "noshowpoint", "showpos", "noshowpos",
        "skipws", "noskipws", "uppercase", "nouppercase", "unitbuf", "nounitbuf",
        "flush", "ends", "ws"
    ));
    
    // 常用标准库函数（带函数签名提示）
    private static final java.util.Map<String, String> STD_FUNCTIONS = new java.util.HashMap<String, String>() {{
        // stdio.h / cstdio
        put("printf", "printf(const char* format, ...)");
        put("scanf", "scanf(const char* format, ...)");
        put("sprintf", "sprintf(char* str, const char* format, ...)");
        put("snprintf", "snprintf(char* str, size_t size, const char* format, ...)");
        put("fprintf", "fprintf(FILE* stream, const char* format, ...)");
        put("fscanf", "fscanf(FILE* stream, const char* format, ...)");
        put("sscanf", "sscanf(const char* str, const char* format, ...)");
        put("puts", "puts(const char* str)");
        put("gets", "gets(char* str)");
        put("putchar", "putchar(int c)");
        put("getchar", "getchar()");
        put("fopen", "fopen(const char* filename, const char* mode)");
        put("fclose", "fclose(FILE* stream)");
        put("fread", "fread(void* ptr, size_t size, size_t count, FILE* stream)");
        put("fwrite", "fwrite(const void* ptr, size_t size, size_t count, FILE* stream)");
        put("fgets", "fgets(char* str, int n, FILE* stream)");
        put("fputs", "fputs(const char* str, FILE* stream)");
        put("fseek", "fseek(FILE* stream, long offset, int origin)");
        put("ftell", "ftell(FILE* stream)");
        put("rewind", "rewind(FILE* stream)");
        put("fflush", "fflush(FILE* stream)");
        put("feof", "feof(FILE* stream)");
        put("ferror", "ferror(FILE* stream)");
        put("perror", "perror(const char* str)");
        put("remove", "remove(const char* filename)");
        put("rename", "rename(const char* oldname, const char* newname)");
        
        // stdlib.h / cstdlib
        put("malloc", "malloc(size_t size)");
        put("calloc", "calloc(size_t num, size_t size)");
        put("realloc", "realloc(void* ptr, size_t size)");
        put("free", "free(void* ptr)");
        put("atoi", "atoi(const char* str)");
        put("atol", "atol(const char* str)");
        put("atof", "atof(const char* str)");
        put("strtol", "strtol(const char* str, char** endptr, int base)");
        put("strtod", "strtod(const char* str, char** endptr)");
        put("rand", "rand()");
        put("srand", "srand(unsigned int seed)");
        put("abs", "abs(int n)");
        put("labs", "labs(long n)");
        put("exit", "exit(int status)");
        put("system", "system(const char* command)");
        put("getenv", "getenv(const char* name)");
        put("qsort", "qsort(void* base, size_t num, size_t size, int(*compar)(const void*, const void*))");
        put("bsearch", "bsearch(const void* key, const void* base, size_t num, size_t size, int(*compar)(const void*, const void*))");
        
        // string.h / cstring
        put("strlen", "strlen(const char* str)");
        put("strcpy", "strcpy(char* dest, const char* src)");
        put("strncpy", "strncpy(char* dest, const char* src, size_t n)");
        put("strcat", "strcat(char* dest, const char* src)");
        put("strncat", "strncat(char* dest, const char* src, size_t n)");
        put("strcmp", "strcmp(const char* str1, const char* str2)");
        put("strncmp", "strncmp(const char* str1, const char* str2, size_t n)");
        put("strchr", "strchr(const char* str, int c)");
        put("strrchr", "strrchr(const char* str, int c)");
        put("strstr", "strstr(const char* str1, const char* str2)");
        put("strtok", "strtok(char* str, const char* delim)");
        put("memset", "memset(void* ptr, int value, size_t num)");
        put("memcpy", "memcpy(void* dest, const void* src, size_t num)");
        put("memmove", "memmove(void* dest, const void* src, size_t num)");
        put("memcmp", "memcmp(const void* ptr1, const void* ptr2, size_t num)");
        
        // math.h / cmath
        put("sqrt", "sqrt(double x)");
        put("pow", "pow(double base, double exp)");
        put("sin", "sin(double x)");
        put("cos", "cos(double x)");
        put("tan", "tan(double x)");
        put("asin", "asin(double x)");
        put("acos", "acos(double x)");
        put("atan", "atan(double x)");
        put("atan2", "atan2(double y, double x)");
        put("exp", "exp(double x)");
        put("log", "log(double x)");
        put("log10", "log10(double x)");
        put("ceil", "ceil(double x)");
        put("floor", "floor(double x)");
        put("round", "round(double x)");
        put("fabs", "fabs(double x)");
        put("fmod", "fmod(double x, double y)");
        
        // algorithm
        put("sort", "sort(iterator first, iterator last)");
        put("find", "find(iterator first, iterator last, const T& value)");
        put("count", "count(iterator first, iterator last, const T& value)");
        put("reverse", "reverse(iterator first, iterator last)");
        put("max", "max(const T& a, const T& b)");
        put("min", "min(const T& a, const T& b)");
        put("swap", "swap(T& a, T& b)");
        put("fill", "fill(iterator first, iterator last, const T& value)");
        put("copy", "copy(iterator first, iterator last, iterator result)");
        put("unique", "unique(iterator first, iterator last)");
        put("lower_bound", "lower_bound(iterator first, iterator last, const T& value)");
        put("upper_bound", "upper_bound(iterator first, iterator last, const T& value)");
        put("binary_search", "binary_search(iterator first, iterator last, const T& value)");
        put("next_permutation", "next_permutation(iterator first, iterator last)");
        put("prev_permutation", "prev_permutation(iterator first, iterator last)");
        put("accumulate", "accumulate(iterator first, iterator last, T init)");
        
        // time.h / ctime
        put("time", "time(time_t* timer)");
        put("clock", "clock()");
        put("difftime", "difftime(time_t end, time_t beginning)");
        put("mktime", "mktime(struct tm* timeptr)");
        put("localtime", "localtime(const time_t* timer)");
        put("gmtime", "gmtime(const time_t* timer)");
        put("strftime", "strftime(char* str, size_t maxsize, const char* format, const struct tm* timeptr)");
        
        // assert.h
        put("assert", "assert(expression)");
    }};

    // 预处理指令
    private static final Set<String> PREPROCESSOR = new HashSet<>(Arrays.asList(
        "include", "define", "undef", "ifdef", "ifndef", "if", "else", "elif",
        "endif", "pragma", "error", "warning", "line"
    ));

    // 用于提取变量和函数的正则表达式
    // 匹配变量声明: type name 或 type name = value
    private static final Pattern VAR_PATTERN = Pattern.compile(
        "\\b((?:const\\s+)?(?:unsigned\\s+)?(?:int|char|float|double|bool|long|short|auto|string|void|size_t|vector|map|set|array)(?:\\s*<[^>]*>)?(?:\\s*[*&])?)\\s+([\\p{L}_][\\p{L}\\p{N}_]*)\\s*(?:[=;,\\[\\)])"
    );
    // 匹配函数声明: type name(
    private static final Pattern FUNC_PATTERN = Pattern.compile(
        "\\b([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\([^)]*\\)\\s*(?:\\{|;)"
    );
    
    /**
     * 函数位置信息
     */
    public static class FunctionLocation {
        public final String name;
        public final int line;       // 行号（0-based）
        public final int column;     // 列号（0-based）
        public final boolean isDefinition;  // true=定义, false=调用
        
        public FunctionLocation(String name, int line, int column, boolean isDefinition) {
            this.name = name;
            this.line = line;
            this.column = column;
            this.isDefinition = isDefinition;
        }
    }
    
    private volatile SymbolIndex symbols = SymbolIndex.empty();
    private final Object symbolTasks = new Object();
    private long symbolGeneration;
    private boolean languageDestroyed;
    private java.util.concurrent.Future<?> symbolTask;
    private java.util.concurrent.CompletableFuture<Void> symbolResult;
    private final java.util.concurrent.ThreadPoolExecutor symbolWorker = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "vkyra-symbol-index");
                thread.setDaemon(true);
                return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /** Synchronous compatibility entry point; publishes one complete immutable snapshot. */
    public void extractIdentifiers(String code) {
        final long generation;
        synchronized (symbolTasks) {
            if (languageDestroyed) return;
            generation = ++symbolGeneration;
            cancelSymbolTask();
        }
        SymbolIndex index = SymbolIndex.parseSnapshot(code);
        synchronized (symbolTasks) {
            if (!languageDestroyed && generation == symbolGeneration) symbols = index;
        }
    }

    /** Only the latest pending edit is parsed; older snapshots can never replace newer symbols. */
    public java.util.concurrent.CompletableFuture<Void> extractIdentifiersAsync(String code) {
        java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<>();
        synchronized (symbolTasks) {
            if (languageDestroyed) {
                result.cancel(false);
                return result;
            }
            final long generation = ++symbolGeneration;
            cancelSymbolTask();
            symbolResult = result;
            symbolTask = symbolWorker.submit(() -> {
                try {
                    SymbolIndex index = SymbolIndex.parseSnapshot(code);
                    synchronized (symbolTasks) {
                        if (languageDestroyed || generation != symbolGeneration) result.cancel(false);
                        else { symbols = index; result.complete(null); }
                    }
                } catch (java.util.concurrent.CancellationException cancelled) {
                    result.cancel(false);
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
            java.util.concurrent.Future<?> owned = symbolTask;
            result.whenComplete((value, error) -> { if (result.isCancelled()) owned.cancel(true); });
        }
        return result;
    }

    private void cancelSymbolTask() {
        if (symbolResult != null) symbolResult.cancel(false);
        if (symbolTask != null) symbolTask.cancel(true);
        symbolWorker.getQueue().clear();
    }

    @Override public void destroy() {
        synchronized (symbolTasks) {
            languageDestroyed = true;
            symbolGeneration++;
            cancelSymbolTask();
            symbolWorker.shutdownNow();
            symbols = SymbolIndex.empty();
            clangdClient = null;
            currentFilePath = null;
        }
        super.destroy();
    }

    public FunctionLocation getFunctionDefinition(String name) { return symbols.getFunctionDefinition(name); }
    public List<FunctionLocation> getFunctionCalls(String name) { return symbols.getFunctionCalls(name); }
    public String getFunctionDefinitionAt(int line, int column) { return symbols.getFunctionDefinitionAt(line, column); }
    public String getFunctionCallAt(int line, int column) { return symbols.getFunctionCallAt(line, column); }
    public String getIdentifierAt(String code, int line, int column) { return symbols.getIdentifierAt(code, line, column); }
    public boolean isFunction(String identifier) { return symbols.isFunction(identifier); }
    public Set<String> getVisibleVariables(int line) { return symbols.getVisibleVariables(line); }
    public Set<String> getVisibleFunctions() { return symbols.getVisibleFunctions(); }
    public Set<String> getExtractedVariables() { return symbols.getExtractedVariables(); }
    public Set<String> getExtractedFunctions() { return symbols.getExtractedFunctions(); }

    /** Builder ownership ends at parseSnapshot(); published sets/maps/lists are immutable. */
    private static final class SymbolIndex {
        static SymbolIndex empty() { return new SymbolIndex().freeze(); }
        static SymbolIndex parseSnapshot(String code) {
            SymbolIndex index = new SymbolIndex();
            index.parse(code);
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            return index.freeze();
        }
        private SymbolIndex freeze() {
            functionDefinitions = java.util.Map.copyOf(functionDefinitions);
            java.util.Map<String, List<FunctionLocation>> calls = new java.util.HashMap<>();
            functionCalls.forEach((name, locations) -> calls.put(name, java.util.List.copyOf(locations)));
            functionCalls = java.util.Map.copyOf(calls);
            globalVariables = java.util.Set.copyOf(globalVariables);
            globalFunctions = java.util.Set.copyOf(globalFunctions);
            for (ScopeInfo scope : allScopes) {
                scope.variables = java.util.Set.copyOf(scope.variables);
                scope.functions = java.util.Set.copyOf(scope.functions);
            }
            allScopes = java.util.List.copyOf(allScopes);
            return this;
        }
        // 函数定义位置 (函数名 -> 行号)
        private java.util.Map<String, FunctionLocation> functionDefinitions = new java.util.HashMap<>();
        
        // 函数调用位置 (函数名 -> 调用位置列表)
        private java.util.Map<String, List<FunctionLocation>> functionCalls = new java.util.HashMap<>();
        
        // 作用域信息类
        private static class ScopeInfo {
            int startPos;      // 作用域开始位置（字符索引）
            int endPos;        // 作用域结束位置（字符索引）
            int startLine;     // 开始行号
            int endLine;       // 结束行号
            Set<String> variables = new HashSet<>();   // 该作用域内的变量
            Set<String> functions = new HashSet<>();   // 该作用域内的函数
            ScopeInfo parent;  // 父作用域
            
            ScopeInfo(int startPos, int startLine) {
                this.startPos = startPos;
                this.startLine = startLine;
                this.endPos = -1;
                this.endLine = -1;
            }
        }
        
        // 全局变量和函数
        private Set<String> globalVariables = new HashSet<>();
        private Set<String> globalFunctions = new HashSet<>();
        
        // 所有作用域列表
        private List<ScopeInfo> allScopes = new ArrayList<>();
        
        // 缓存的代码
    
    
        /**
         * 从代码中提取变量和函数名（带作用域分析）
         */
        private void parse(String code) {
            globalVariables.clear();
            globalFunctions.clear();
            allScopes.clear();
            functionDefinitions.clear();
            functionCalls.clear();
            
            if (code == null || code.isEmpty()) {
                return;
            }
            
            
            // 移除注释以避免误匹配，但保留换行符以维持行号
            String cleanCode = com.vkyra.code.editor.language.CppLexicalSnapshot.codeOnly(code);
            
            // 使用 TextMate 屏蔽后的等长源码构建作用域，保留 UTF-16 位置信息
            buildScopeTree(cleanCode);
            
            // 第二步：提取每个作用域内的变量（使用清理后的代码）
            extractVariablesInScopes(code, cleanCode);
            
            // 第三步：提取全局函数
            extractGlobalFunctions(cleanCode);
            
            // 第四步：提取函数定义和调用位置
            extractFunctionLocations(cleanCode);
        }
        
        /**
         * 构建作用域树
         */
        private void buildScopeTree(String code) {
            Stack<ScopeInfo> scopeStack = new Stack<>();
            int line = 0;
            for (int index = 0; index < code.length(); index++) {
                if ((index & 1023) == 0 && Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException();
                char value = code.charAt(index);
                if (value == '\n') line++;
                if (value == '{') {
                    ScopeInfo scope = new ScopeInfo(index, line);
                    if (!scopeStack.isEmpty()) scope.parent = scopeStack.peek();
                    scopeStack.push(scope);
                    allScopes.add(scope);
                } else if (value == '}' && !scopeStack.isEmpty()) {
                    ScopeInfo scope = scopeStack.pop();
                    scope.endPos = index;
                    scope.endLine = line;
                }
            }
        }

        /**
         * 提取每个作用域内的变量
         */
        private void extractVariablesInScopes(String originalCode, String cleanCode) {
            // 提取全局变量（不在任何作用域内的变量）
            int globalEnd = allScopes.isEmpty() ? cleanCode.length() : allScopes.get(0).startPos;
            if (globalEnd > 0 && globalEnd <= cleanCode.length()) {
                String globalCode = cleanCode.substring(0, globalEnd);
                extractVariablesFromCode(globalCode, globalVariables);
            }
            
            // 提取每个作用域内的变量
            for (ScopeInfo scope : allScopes) {
                if (scope.endPos > scope.startPos && scope.endPos <= cleanCode.length()) {
                    String scopeCode = cleanCode.substring(scope.startPos + 1, scope.endPos);
                    
                    // 只提取直接在该作用域内的变量（不包括子作用域）
                    String directCode = removeNestedScopes(scopeCode);
                    extractVariablesFromCode(directCode, scope.variables);
                }
            }
        }
        
        /**
         * 移除嵌套的作用域内容，只保留当前层级的代码
         */
        private String removeNestedScopes(String code) {
            StringBuilder result = new StringBuilder();
            int depth = 0;
            
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                
                if (c == '{') {
                    depth++;
                    result.append(' ');
                } else if (c == '}') {
                    depth--;
                    result.append(' ');
                } else {
                    result.append(depth == 0 || c == '\n' || c == '\r' ? c : ' ');
                }
            }
            return result.toString();
        }
        
        /**
         * 从代码片段中提取变量
         */
        private void extractVariablesFromCode(String code, Set<String> variables) {
            Matcher varMatcher = VAR_PATTERN.matcher(code);
            while (varMatcher.find()) {
                String varName = varMatcher.group(2);
                if (varName != null && !KEYWORDS.contains(varName) && !TYPES.contains(varName)) {
                    variables.add(varName);
                }
            }
            
            // 简单模式：type name; 或 type name = 
            Pattern simpleVar = Pattern.compile("\\b([\\p{L}_][\\p{L}\\p{N}_]*)\\s+([\\p{L}_][\\p{L}\\p{N}_]*)\\s*[=;]");
            Matcher simpleMatcher = simpleVar.matcher(code);
            while (simpleMatcher.find()) {
                String typeName = simpleMatcher.group(1);
                String varName = simpleMatcher.group(2);
                if (varName != null && !KEYWORDS.contains(varName) && !TYPES.contains(varName)
                    && (TYPES.contains(typeName) || STL_TYPES.contains(typeName) || typeName.equals("auto"))) {
                    variables.add(varName);
                }
            }
            
            // for 循环变量: for (int i = 0; ...)
            Pattern forVar = Pattern.compile("for\\s*\\(\\s*(?:int|auto|size_t)\\s+([\\p{L}_][\\p{L}\\p{N}_]*)");
            Matcher forMatcher = forVar.matcher(code);
            while (forMatcher.find()) {
                String varName = forMatcher.group(1);
                if (varName != null) {
                    variables.add(varName);
                }
            }
        }
        
        /**
         * 提取全局函数
         */
        private void extractGlobalFunctions(String code) {
            Matcher funcMatcher = FUNC_PATTERN.matcher(code);
            while (funcMatcher.find()) {
                String funcName = funcMatcher.group(1);
                if (funcName != null && !KEYWORDS.contains(funcName) && !TYPES.contains(funcName)
                    && !funcName.equals("if") && !funcName.equals("while") && !funcName.equals("for")
                    && !funcName.equals("switch") && !funcName.equals("catch")) {
                    globalFunctions.add(funcName);
                }
            }
        }
        
        /**
         * 提取函数定义和调用位置
         */
        private void extractFunctionLocations(String code) {
            String[] lines = code.split("\n", -1);
            
            // 函数调用模式: 函数名(
            Pattern callPattern = Pattern.compile("\\b([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\(");
            
            // 先扫描找出所有函数定义
            // 支持两种风格:
            // 1. int add(int a, int b) {
            // 2. int add(int a, int b)
            //    {
            Pattern funcDeclPattern = Pattern.compile(
                "^\\s*(?:static\\s+|inline\\s+|virtual\\s+|extern\\s+)*" +
                "(?:const\\s+)?(?:unsigned\\s+|signed\\s+)?" +
                "(?:[\\p{L}_][\\p{L}\\p{N}_]*(?:\\s*<[^>]*>)?(?:\\s*[*&])?\\s+)+" +
                "([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\([^)]*\\)\\s*(?:const)?\\s*$"
            );
            
            for (int lineNum = 0; lineNum < lines.length; lineNum++) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                String line = lines[lineNum];
                String trimmedLine = line.trim();
                
                // 检查函数定义 - 风格1: 大括号在同一行
                if (trimmedLine.endsWith("{") && trimmedLine.contains("(") && trimmedLine.contains(")")) {
                    // 移除末尾的 { 再匹配
                    String withoutBrace = trimmedLine.substring(0, trimmedLine.length() - 1).trim();
                    Matcher m = funcDeclPattern.matcher(withoutBrace);
                    if (m.find()) {
                        String funcName = m.group(1);
                        if (isValidFunctionName(funcName)) {
                            int col = line.indexOf(funcName);
                            functionDefinitions.put(funcName, new FunctionLocation(funcName, lineNum, col, true));
                            continue;
                        }
                    }
                    // 简单匹配: type name(...) {
                    Pattern simplePattern = Pattern.compile("([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\([^)]*\\)\\s*\\{\\s*$");
                    Matcher sm = simplePattern.matcher(trimmedLine);
                    if (sm.find()) {
                        String funcName = sm.group(1);
                        if (isValidFunctionName(funcName)) {
                            int col = line.indexOf(funcName);
                            functionDefinitions.put(funcName, new FunctionLocation(funcName, lineNum, col, true));
                            continue;
                        }
                    }
                }
                
                // 检查函数定义 - 风格2: 大括号在下一行
                if (trimmedLine.endsWith(")") && trimmedLine.contains("(") && lineNum + 1 < lines.length) {
                    String nextLine = lines[lineNum + 1].trim();
                    if (nextLine.startsWith("{")) {
                        Matcher m = funcDeclPattern.matcher(trimmedLine);
                        if (m.find()) {
                            String funcName = m.group(1);
                            if (isValidFunctionName(funcName)) {
                                int col = line.indexOf(funcName);
                                functionDefinitions.put(funcName, new FunctionLocation(funcName, lineNum, col, true));
                                continue;
                            }
                        }
                        // 简单匹配
                        Pattern simplePattern = Pattern.compile("([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\([^)]*\\)\\s*$");
                        Matcher sm = simplePattern.matcher(trimmedLine);
                        if (sm.find()) {
                            String funcName = sm.group(1);
                            if (isValidFunctionName(funcName)) {
                                int col = line.indexOf(funcName);
                                functionDefinitions.put(funcName, new FunctionLocation(funcName, lineNum, col, true));
                                continue;
                            }
                        }
                    }
                }
            }
            
            // 再扫描找出所有函数调用
            for (int lineNum = 0; lineNum < lines.length; lineNum++) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                String line = lines[lineNum];
                
                Matcher callMatcher = callPattern.matcher(line);
                while (callMatcher.find()) {
                    String funcName = callMatcher.group(1);
                    if (!isValidFunctionName(funcName)) continue;
                    if (STL_TYPES.contains(funcName)) continue;
                    
                    int col = callMatcher.start(1);
                    
                    // 检查是否是定义行（跳过定义本身）
                    FunctionLocation def = functionDefinitions.get(funcName);
                    if (def != null && def.line == lineNum) {
                        continue;
                    }
                    
                    FunctionLocation loc = new FunctionLocation(funcName, lineNum, col, false);
                    functionCalls.computeIfAbsent(funcName, k -> new ArrayList<>()).add(loc);
                }
            }
        }
        
        /**
         * 检查是否是有效的函数名（不是关键字、类型等）
         */
        private boolean isValidFunctionName(String name) {
            if (name == null || name.isEmpty()) return false;
            if (KEYWORDS.contains(name)) return false;
            if (TYPES.contains(name)) return false;
            if (name.equals("if") || name.equals("while") || name.equals("for") ||
                name.equals("switch") || name.equals("catch") || name.equals("return")) {
                return false;
            }
            return true;
        }
        
        /**
         * 获取函数定义位置
         * @param funcName 函数名
         * @return 函数定义位置，如果不存在返回 null
         */
        public FunctionLocation getFunctionDefinition(String funcName) {
            return functionDefinitions.get(funcName);
        }
        
        /**
         * 获取函数的所有调用位置
         * @param funcName 函数名
         * @return 调用位置列表，如果没有调用返回空列表
         */
        public List<FunctionLocation> getFunctionCalls(String funcName) {
            return functionCalls.getOrDefault(funcName, new ArrayList<>());
        }
        
        /**
         * 检查指定位置是否是函数定义
         * @param line 行号
         * @param column 列号
         * @return 如果是函数定义返回函数名，否则返回 null
         */
        public String getFunctionDefinitionAt(int line, int column) {
            for (java.util.Map.Entry<String, FunctionLocation> entry : functionDefinitions.entrySet()) {
                FunctionLocation loc = entry.getValue();
                if (loc.line == line && column >= loc.column && column < loc.column + loc.name.length()) {
                    return loc.name;
                }
            }
            return null;
        }
        
        /**
         * 检查指定位置是否是函数调用
         * @param line 行号
         * @param column 列号
         * @return 如果是函数调用返回函数名，否则返回 null
         */
        public String getFunctionCallAt(int line, int column) {
            for (java.util.Map.Entry<String, List<FunctionLocation>> entry : functionCalls.entrySet()) {
                for (FunctionLocation loc : entry.getValue()) {
                    if (loc.line == line && column >= loc.column && column < loc.column + loc.name.length()) {
                        return loc.name;
                    }
                }
            }
            return null;
        }
        
        /**
         * 获取指定位置的标识符（函数名或变量名）
         * @param code 代码内容
         * @param line 行号
         * @param column 列号
         * @return 标识符，如果不存在返回 null
         */
        public String getIdentifierAt(String code, int line, int column) {
            String[] lines = code.split("\n", -1);
            if (line < 0 || line >= lines.length) return null;
            
            String lineText = lines[line];
            if (column < 0 || column >= lineText.length()) return null;
            
            // 向左扩展找到标识符开始
            int start = column;
            while (start > 0 && (Character.isLetterOrDigit(lineText.charAt(start - 1)) || lineText.charAt(start - 1) == '_')) {
                start--;
            }
            
            // 向右扩展找到标识符结束
            int end = column;
            while (end < lineText.length() && (Character.isLetterOrDigit(lineText.charAt(end)) || lineText.charAt(end) == '_')) {
                end++;
            }
            
            if (start < end) {
                return lineText.substring(start, end);
            }
            return null;
        }
        
        /**
         * 判断标识符是否是函数（定义或调用）
         */
        public boolean isFunction(String identifier) {
            return functionDefinitions.containsKey(identifier) || functionCalls.containsKey(identifier);
        }
        
        /**
         * 根据光标位置获取当前作用域内可见的变量
         */
        public Set<String> getVisibleVariables(int line) {
            Set<String> visible = new HashSet<>();
            
            // 全局变量始终可见
            visible.addAll(globalVariables);
            
            // 从内到外遍历所有包含当前行的作用域
            for (ScopeInfo scope : allScopes) {
                // 使用行号判断是否在作用域内
                if (scope.startLine <= line && (scope.endLine < 0 || scope.endLine >= line)) {
                    visible.addAll(scope.variables);
                }
            }
            
            return visible;
        }
        
        /**
         * 获取所有函数（函数是全局可见的）
         */
        public Set<String> getVisibleFunctions() {
            return globalFunctions;
        }
        
        /**
         * 获取提取的变量集合（兼容旧接口）
         */
        public Set<String> getExtractedVariables() {
            Set<String> all = new HashSet<>(globalVariables);
            for (ScopeInfo scope : allScopes) {
                all.addAll(scope.variables);
            }
            return all;
        }
        
        /**
         * 获取提取的函数集合（兼容旧接口）
         */
        public Set<String> getExtractedFunctions() {
            return globalFunctions;
        }
    }

    @Override
    public int getInterruptionLevel() {
        return INTERRUPTION_LEVEL_STRONG;
    }

    @Override
    public void requireAutoComplete(@NonNull ContentReference content,
                                    @NonNull CharPosition position,
                                    @NonNull CompletionPublisher publisher,
                                    @NonNull Bundle extraArguments) {
        // 检查是否在 #include < 或 #include " 后面
        String lineText = content.getLine(position.getLine()).toString();
        int col = position.getColumn();
        
        // 检测 #include <xxx 或 #include "xxx
        if (isInIncludeDirective(lineText, col)) {
            String headerPrefix = getIncludePrefix(lineText, col);
            boolean useAngleBracket = isAngleBracketInclude(lineText);
            boolean hasClosingChar = hasIncludeClosingChar(lineText, col, useAngleBracket);
            addHeaderCompletions(publisher, headerPrefix, useAngleBracket, hasClosingChar);
            return;
        }
        
        // 检查是否是触发字符（. -> ::）
        boolean isTriggerChar = false;
        String triggerPrefix = "";  // 触发字符场景下的自定义前缀
        if (col > 0) {
            char lastChar = lineText.charAt(col - 1);
            if (lastChar == '.') {
                isTriggerChar = true;
                triggerPrefix = ".";
            } else if (lastChar == ':' && col > 1 && lineText.charAt(col - 2) == ':') {
                isTriggerChar = true;
                triggerPrefix = "::";
            } else if (lastChar == '>' && col > 1 && lineText.charAt(col - 2) == '-') {
                isTriggerChar = true;
                triggerPrefix = "->";
            }
        }
        
        // 获取标识符前缀
        String prefix = getPrefix(content, position);
        
        // 如果 prefix 为空且不是触发字符，直接返回
        if (prefix.isEmpty() && !isTriggerChar) return;
        
        // 如果 clangd 可用，使用 clangd 补全
        boolean clangdAvailable = isClangdAvailable();
        
        if (clangdAvailable) {
            // 触发字符场景下，prefix 可能为空，但仍需请求 clangd
            // 传递触发字符给 clangd，让它知道这是成员补全
            requestClangdCompletion(content, position, prefix, publisher, isTriggerChar ? triggerPrefix : null);
        } else {
            // 回退到本地补全（本地补全不支持触发字符）
            if (!prefix.isEmpty()) {
                addLocalCompletions(content, position, prefix, publisher);
            }
        }
    }
    
    /**
     * 请求 clangd 补全
     * @param triggerChar 触发字符（如 "." "->" "::"），null 表示普通补全
     */
    private void requestClangdCompletion(ContentReference content, CharPosition position,
                                         String prefix, CompletionPublisher publisher, String trigger) {
        ClangdClient client = clangdClient;
        String path = currentFilePath;
        if (client == null || path == null) {
            if (!prefix.isEmpty()) addLocalCompletions(content, position, prefix, publisher);
            return;
        }
        String snapshot = content.toString();
        // Both notifications and requests share the session command queue: no sleep is needed.
        client.updateDocument(path, snapshot, 1);
        java.util.concurrent.CompletableFuture<java.util.List<org.eclipse.lsp4j.CompletionItem>> request =
                client.completionItemsFuture(path, position.line, position.column, trigger);
        try {
            java.util.List<org.eclipse.lsp4j.CompletionItem> items = request.get(3, java.util.concurrent.TimeUnit.SECONDS);
            publisher.checkCancelled();
            int published = 0;
            for (org.eclipse.lsp4j.CompletionItem item : items) {
                String filter = item.getFilterText() == null ? item.getLabel() : item.getFilterText();
                if (filter == null || !filter.toLowerCase(java.util.Locale.ROOT)
                        .startsWith(prefix.toLowerCase(java.util.Locale.ROOT))) continue;
                publisher.addItem(new com.vkyra.code.editor.language.LspCompletionProposal(item, prefix.length(), snapshot));
                if (++published >= 200) break;
            }
            if (published == 0 && !prefix.isEmpty()) addLocalCompletions(content, position, prefix, publisher);
        } catch (InterruptedException interrupted) {
            request.cancel(true);
            Thread.currentThread().interrupt();
            publisher.checkCancelled();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
                 | java.util.concurrent.CancellationException failed) {
            request.cancel(true);
            publisher.checkCancelled();
            if (!prefix.isEmpty()) addLocalCompletions(content, position, prefix, publisher);
        }
    }

    private void addSnippetCompletions(String prefix, CompletionPublisher publisher) {
        String lowerPrefix = prefix.toLowerCase();
        
        // 代码片段补全
        if ("main".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("main", "main函数模板", prefix.length(),
                "int main() {\n    \n    return 0;\n}"));
        }
        if ("mainarg".startsWith(lowerPrefix) || "main".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("mainarg", "main函数(带参数)", prefix.length(),
                "int main(int argc, char* argv[]) {\n    \n    return 0;\n}"));
        }
        if ("inc".startsWith(lowerPrefix) || "include".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("include", "#include <>", prefix.length(),
                "include <>"));
            publisher.addItem(new SimpleCompletionItem("include", "#include \"\"", prefix.length(),
                "include \"\""));
        }
        if ("for".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("fori", "for循环", prefix.length(),
                "for (int i = 0; i < n; i++) {\n    \n}"));
            publisher.addItem(new SimpleCompletionItem("forr", "for范围循环", prefix.length(),
                "for (auto& item : container) {\n    \n}"));
        }
        if ("while".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("while", "while循环", prefix.length(),
                "while (condition) {\n    \n}"));
        }
        if ("if".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("ife", "if-else语句", prefix.length(),
                "if (condition) {\n    \n} else {\n    \n}"));
        }
        if ("class".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("class", "类定义", prefix.length(),
                "class ClassName {\npublic:\n    ClassName();\n    ~ClassName();\n\nprivate:\n    \n};"));
        }
        if ("struct".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("struct", "结构体定义", prefix.length(),
                "struct StructName {\n    \n};"));
        }
    }
    
    /**
     * 本地补全（回退方案）
     */
    private void addLocalCompletions(ContentReference content, CharPosition position, String prefix, CompletionPublisher publisher) {
        String lowerPrefix = prefix.toLowerCase();
        
        // 代码片段补全
        if ("main".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("main", "main函数模板", prefix.length(),
                "int main() {\n    \n    return 0;\n}"));
        }
        if ("mainarg".startsWith(lowerPrefix) || "main".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("mainarg", "main函数(带参数)", prefix.length(),
                "int main(int argc, char* argv[]) {\n    \n    return 0;\n}"));
        }
        if ("inc".startsWith(lowerPrefix) || "include".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("include", "#include <>", prefix.length(),
                "include <>"));
            publisher.addItem(new SimpleCompletionItem("include", "#include \"\"", prefix.length(),
                "include \"\""));
            publisher.addItem(new SimpleCompletionItem("include", "#include <iostream>", prefix.length(),
                "include <iostream>"));
            publisher.addItem(new SimpleCompletionItem("include", "#include <string>", prefix.length(),
                "include <string>"));
            publisher.addItem(new SimpleCompletionItem("include", "#include <vector>", prefix.length(),
                "include <vector>"));
            publisher.addItem(new SimpleCompletionItem("include", "#include <algorithm>", prefix.length(),
                "include <algorithm>"));
        }
        if ("cout".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("cout", "cout输出", prefix.length(),
                "cout <<  << endl;"));
        }
        if ("cin".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("cin", "cin输入", prefix.length(),
                "cin >> ;"));
        }
        if ("for".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("for", "for循环", prefix.length(),
                "for (int i = 0; i < n; i++) {\n    \n}"));
            publisher.addItem(new SimpleCompletionItem("forr", "for范围循环", prefix.length(),
                "for (auto& item : container) {\n    \n}"));
        }
        if ("while".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("while", "while循环", prefix.length(),
                "while (condition) {\n    \n}"));
        }
        if ("if".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("if", "if语句", prefix.length(),
                "if (condition) {\n    \n}"));
            publisher.addItem(new SimpleCompletionItem("ife", "if-else语句", prefix.length(),
                "if (condition) {\n    \n} else {\n    \n}"));
        }
        if ("class".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("class", "类定义", prefix.length(),
                "class ClassName {\npublic:\n    ClassName();\n    ~ClassName();\n\nprivate:\n    \n};"));
        }
        if ("struct".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("struct", "结构体定义", prefix.length(),
                "struct StructName {\n    \n};"));
        }
        if ("func".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("func", "函数定义", prefix.length(),
                "void functionName() {\n    \n}"));
        }
        if ("switch".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("switch", "switch语句", prefix.length(),
                "switch (expression) {\n    case value1:\n        break;\n    case value2:\n        break;\n    default:\n        break;\n}"));
        }
        if ("try".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("try", "try-catch", prefix.length(),
                "try {\n    \n} catch (const exception& e) {\n    \n}"));
        }
        if ("vec".startsWith(lowerPrefix) || "vector".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("vec", "vector定义", prefix.length(),
                "vector<int> v;"));
        }
        if ("map".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("map", "map定义", prefix.length(),
                "map<string, int> m;"));
        }
        if ("using".startsWith(lowerPrefix)) {
            publisher.addItem(new SimpleCompletionItem("using", "using namespace std", prefix.length(),
                "using namespace std;"));
        }

        // 插件自定义补全模板（.hook -> completion）
        addPluginHookCompletions(prefix, publisher);
        
        // 只有前缀长度>=2才补全关键字
        if (prefix.length() >= 2) {
            // 添加关键字补全
            for (String kw : KEYWORDS) {
                if (kw.startsWith(lowerPrefix)) {
                    publisher.addItem(new SimpleCompletionItem(kw, "关键字", prefix.length(), kw));
                }
            }
            // 添加类型补全
            for (String type : TYPES) {
                if (type.startsWith(lowerPrefix)) {
                    publisher.addItem(new SimpleCompletionItem(type, "类型", prefix.length(), type));
                }
            }
            // 添加 STL 补全
            for (String stl : STL_TYPES) {
                if (stl.startsWith(lowerPrefix)) {
                    publisher.addItem(new SimpleCompletionItem(stl, "STL", prefix.length(), stl));
                }
            }
            
            // 添加从当前文件提取的变量补全（作用域感知）
            Set<String> visibleVars = getVisibleVariables(position.getLine());
            for (String var : visibleVars) {
                if (var.toLowerCase().startsWith(lowerPrefix) && !var.equals(prefix)) {
                    publisher.addItem(new SimpleCompletionItem(var, "变量", prefix.length(), var));
                }
            }
            
            // 添加从当前文件提取的函数补全（函数全局可见）
            for (String func : getVisibleFunctions()) {
                if (func.toLowerCase().startsWith(lowerPrefix) && !func.equals(prefix)) {
                    publisher.addItem(new SimpleCompletionItem(func + "()", "函数", prefix.length(), func + "()"));
                }
            }
            
            // 添加标准库函数补全
            for (java.util.Map.Entry<String, String> entry : STD_FUNCTIONS.entrySet()) {
                String funcName = entry.getKey();
                if (funcName.toLowerCase().startsWith(lowerPrefix)) {
                    String signature = entry.getValue();
                    publisher.addItem(new SimpleCompletionItem(funcName + "()", signature, prefix.length(), funcName + "()"));
                }
            }
        }
    }

    /**
     * 从插件 hook 读取 C++ 自定义补全模板。
     * 格式参考：plugin/.hook -> completion 数组。
     */
    private void addPluginHookCompletions(String prefix, CompletionPublisher publisher) {
        if (context == null) return;
        try {
            List<PluginHookManager.CompletionConfig> configs =
                PluginManager.getHookManager(context).getCompletionItems("cpp", prefix);
            for (PluginHookManager.CompletionConfig c : configs) {
                String label = (c.label == null || c.label.isEmpty()) ? c.trigger : c.label;
                String desc = (c.desc == null || c.desc.isEmpty()) ? "插件模板" : c.desc;
                publisher.addItem(new SimpleCompletionItem(label, desc, prefix.length(), c.insert));
            }
        } catch (Exception e) {
            android.util.Log.e("CppLanguage", "Failed to add plugin hook completions", e);
        }
    }
    
    // 检查是否在 #include 指令中
    private boolean isInIncludeDirective(String line, int col) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("#include")) return false;
        
        int angleStart = line.indexOf('<');
        int quoteStart = line.indexOf('"');
        
        if (angleStart >= 0 && col > angleStart) {
            int angleEnd = line.indexOf('>', angleStart);
            return angleEnd < 0 || col <= angleEnd;
        }
        if (quoteStart >= 0 && col > quoteStart) {
            int quoteEnd = line.indexOf('"', quoteStart + 1);
            return quoteEnd < 0 || col <= quoteEnd;
        }
        return false;
    }
    
    // 获取 #include 中已输入的前缀
    private String getIncludePrefix(String line, int col) {
        int angleStart = line.indexOf('<');
        int quoteStart = line.indexOf('"');
        
        int start = -1;
        if (angleStart >= 0 && col > angleStart) {
            start = angleStart + 1;
        } else if (quoteStart >= 0 && col > quoteStart) {
            start = quoteStart + 1;
        }
        
        if (start >= 0 && start < col) {
            return line.substring(start, col);
        }
        return "";
    }
    
    // 添加头文件补全
    private void addHeaderCompletions(CompletionPublisher publisher, String prefix, boolean useAngleBracket, boolean hasClosingChar) {
        if (context == null) return;
        
        String lowerPrefix = prefix.toLowerCase();
        int prefixLen = prefix.length();
        // 如果行尾已经有 > 或 "，就不再添加
        String suffix = hasClosingChar ? "" : (useAngleBracket ? ">" : "\"");
        
        // 从实际 include 目录扫描的所有头文件
        for (String h : CppHeaders.getAllHeaders(context)) {
            if (h.toLowerCase().startsWith(lowerPrefix)) {
                publisher.addItem(new SimpleCompletionItem(h, "头文件", prefixLen, h + suffix));
            }
        }
    }
    
    // 检查光标后面是否已经有闭合字符（> 或 "）
    private boolean hasIncludeClosingChar(String line, int col, boolean useAngleBracket) {
        char closingChar = useAngleBracket ? '>' : '"';
        // 检查光标位置到行尾是否有闭合字符
        for (int i = col; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == closingChar) {
                return true;
            }
            // 如果遇到其他非空白字符，停止检查
            if (!Character.isWhitespace(c) && c != closingChar) {
                break;
            }
        }
        return false;
    }
    
    // 检查是否是尖括号包含
    private boolean isAngleBracketInclude(String line) {
        int angleStart = line.indexOf('<');
        int quoteStart = line.indexOf('"');
        
        if (angleStart >= 0 && (quoteStart < 0 || angleStart < quoteStart)) {
            return true;
        }
        return false;
    }
    
    private String getPrefix(ContentReference content, CharPosition pos) {
        int col = pos.getColumn();
        if (col == 0) return "";
        CharSequence line = content.getLine(pos.getLine());
        int start = col - 1;
        while (start >= 0 && (Character.isLetterOrDigit(line.charAt(start)) || line.charAt(start) == '_')) {
            start--;
        }
        return line.subSequence(start + 1, col).toString();
    }

    public static boolean isKeyword(String word) { return KEYWORDS.contains(word); }
    public static boolean isType(String word) { return TYPES.contains(word); }
    public static boolean isStlType(String word) { return STL_TYPES.contains(word); }
    public static boolean isPreprocessor(String word) { return PREPROCESSOR.contains(word); }

}
