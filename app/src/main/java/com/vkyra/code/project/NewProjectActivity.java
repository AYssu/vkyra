package com.vkyra.code.project;

import com.vkyra.code.R;
import com.vkyra.code.editor.EditorActivity;
import com.vkyra.code.settings.AppPreferences;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class NewProjectActivity extends AppCompatActivity {
    
    private TextInputEditText etProjectName;
    private TextView tvProjectPath;
    private RadioGroup rgTemplate;
    private MaterialSwitch switchGitInit;
    private MaterialSwitch switchReadme;
    
    private String projectRoot;
    
    // 模板类型
    public static final int TEMPLATE_EMPTY = 0;
    public static final int TEMPLATE_CPP = 1;
    public static final int TEMPLATE_C = 2;
    public static final int TEMPLATE_NDK = 3;
    public static final int TEMPLATE_MAKE = 4;
    public static final int TEMPLATE_CMAKE = 5;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF121212);
            getWindow().setNavigationBarColor(0xFF121212);
        }
        
        setContentView(R.layout.activity_new_project);
        
        projectRoot = AppPreferences.getProjectRoot(this);
        
        initViews();
        setupListeners();
    }
    
    private void initViews() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        
        com.google.android.material.textfield.TextInputLayout tilProjectName = findViewById(R.id.til_project_name);
        etProjectName = findViewById(R.id.et_project_name);
        tvProjectPath = findViewById(R.id.tv_project_path);
        rgTemplate = findViewById(R.id.rg_template);
        switchGitInit = findViewById(R.id.switch_git_init);
        switchReadme = findViewById(R.id.switch_readme);
        
        // 设置输入框默认 hint 颜色（未聚焦时）
        tilProjectName.setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(0xFFB0B0B0));
        
        MaterialButton btnCancel = findViewById(R.id.btn_cancel);
        MaterialButton btnCreate = findViewById(R.id.btn_create);
        
        btnCancel.setOnClickListener(v -> finish());
        btnCreate.setOnClickListener(v -> createProject());
        
        updateProjectPath("");
        
        // 自动聚焦到项目名称输入框并弹出键盘
        etProjectName.requestFocus();
        etProjectName.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm = 
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etProjectName, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        }, 200);
    }
    
    private void setupListeners() {
        etProjectName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateProjectPath(s.toString());
            }
            
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }
    
    private void updateProjectPath(String projectName) {
        if (projectName.isEmpty()) {
            tvProjectPath.setText("项目路径: " + projectRoot + "/");
        } else {
            tvProjectPath.setText("项目路径: " + projectRoot + "/" + projectName);
        }
    }
    
    private int getSelectedTemplate() {
        int checkedId = rgTemplate.getCheckedRadioButtonId();
        if (checkedId == R.id.rb_empty) return TEMPLATE_EMPTY;
        if (checkedId == R.id.rb_cpp) return TEMPLATE_CPP;
        if (checkedId == R.id.rb_c) return TEMPLATE_C;
        if (checkedId == R.id.rb_ndk) return TEMPLATE_NDK;
        if (checkedId == R.id.rb_make) return TEMPLATE_MAKE;
        if (checkedId == R.id.rb_cmake) return TEMPLATE_CMAKE;
        return TEMPLATE_CPP;
    }
    
    private void createProject() {
        String projectName = etProjectName.getText().toString().trim();
        
        if (projectName.isEmpty()) {
            Toast.makeText(this, "请输入项目名称", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 检查项目名称是否合法
        if (!isValidProjectName(projectName)) {
            Toast.makeText(this, "项目名称包含非法字符", Toast.LENGTH_SHORT).show();
            return;
        }
        
        File projectDir = new File(projectRoot, projectName);
        
        // 检查项目是否已存在
        if (projectDir.exists()) {
            Toast.makeText(this, "项目已存在", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 确保根目录存在
        File rootDir = new File(projectRoot);
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
        
        // 创建项目目录
        if (!projectDir.mkdirs()) {
            Toast.makeText(this, "创建项目目录失败", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 根据模板创建文件
        int template = getSelectedTemplate();
        boolean success = createTemplateFiles(projectDir, projectName, template);
        
        // 创建README
        if (switchReadme.isChecked()) {
            createReadme(projectDir, projectName);
        }
        
        if (success) {
            // 获取默认打开文件
            String defaultFile = getDefaultFile(template);
            // 初始化 .akode 项目配置（先于 Git 初始化）
            RecentProjectManager.initProject(projectDir.getAbsolutePath(), projectName, getTemplateName(template), defaultFile);
            
            // 初始化 Git 仓库（在 .akode 之后）
            if (switchGitInit.isChecked()) {
                initGitRepository(projectDir);
            }
            
            showSuccessDialog(projectDir.getAbsolutePath());
        } else {
            Toast.makeText(this, "创建项目失败", Toast.LENGTH_SHORT).show();
        }
    }
    
    private String getDefaultFile(int template) {
        switch (template) {
            case TEMPLATE_EMPTY: return null;
            case TEMPLATE_CPP: return "main.cpp";
            case TEMPLATE_C: return "main.c";
            case TEMPLATE_NDK: return "jni/main.cpp";
            case TEMPLATE_MAKE: return "main.cpp";
            case TEMPLATE_CMAKE: return "main.cpp";
            default: return null;
        }
    }
    
    private String getTemplateName(int template) {
        switch (template) {
            case TEMPLATE_EMPTY: return "empty";
            case TEMPLATE_CPP: return "cpp";
            case TEMPLATE_C: return "c";
            case TEMPLATE_NDK: return "ndk";
            case TEMPLATE_MAKE: return "make";
            case TEMPLATE_CMAKE: return "cmake";
            default: return "unknown";
        }
    }
    
    private boolean isValidProjectName(String name) {
        // 允许中文、英文、数字、下划线、横线，但不允许特殊文件系统字符
        // 禁止: / \ : * ? " < > |
        return !name.isEmpty() && !name.matches(".*[/\\\\:*?\"<>|].*");
    }
    
    private boolean createTemplateFiles(File projectDir, String projectName, int template) {
        try {
            switch (template) {
                case TEMPLATE_EMPTY:
                    return true;
                case TEMPLATE_CPP:
                    return createCppTemplate(projectDir, projectName);
                case TEMPLATE_C:
                    return createCTemplate(projectDir, projectName);
                case TEMPLATE_NDK:
                    return createNdkTemplate(projectDir, projectName);
                case TEMPLATE_MAKE:
                    return createMakeTemplate(projectDir, projectName);
                case TEMPLATE_CMAKE:
                    return createCMakeTemplate(projectDir, projectName);
                default:
                    return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    private boolean createCppTemplate(File projectDir, String projectName) throws IOException {
        String mainCpp = "#include <iostream>\n\nint main() {\n    std::cout << \"Hello, " + projectName + "!\" << std::endl;\n    return 0;\n}\n";
        writeFile(new File(projectDir, "main.cpp"), mainCpp);
        return true;
    }
    
    private boolean createCTemplate(File projectDir, String projectName) throws IOException {
        String mainC = "#include <stdio.h>\n\nint main() {\n    printf(\"Hello, " + projectName + "!\\n\");\n    return 0;\n}\n";
        writeFile(new File(projectDir, "main.c"), mainC);
        return true;
    }
    
    private boolean createNdkTemplate(File projectDir, String projectName) throws IOException {
        // 创建jni目录
        File jniDir = new File(projectDir, "jni");
        jniDir.mkdirs();
        
        // Android.mk
        String androidMk = "LOCAL_PATH := $(call my-dir)\n\ninclude $(CLEAR_VARS)\nLOCAL_MODULE := " + projectName + "\nLOCAL_SRC_FILES := main.cpp\ninclude $(BUILD_EXECUTABLE)\n";
        writeFile(new File(jniDir, "Android.mk"), androidMk);
        
        // Application.mk
        String appMk = "APP_ABI := arm64-v8a\nAPP_STL := c++_static\nAPP_CPPFLAGS := -std=c++17\n";
        writeFile(new File(jniDir, "Application.mk"), appMk);
        
        // main.cpp
        String mainCpp = "#include <iostream>\n\nint main() {\n    std::cout << \"Hello from NDK!\" << std::endl;\n    return 0;\n}\n";
        writeFile(new File(jniDir, "main.cpp"), mainCpp);
        
        return true;
    }
    
    private boolean createMakeTemplate(File projectDir, String projectName) throws IOException {
        // Makefile
        String makefile = "CC = clang\nCXX = clang++\nCFLAGS = -Wall -O2\nCXXFLAGS = -Wall -O2 -std=c++17\n\nTARGET = " + projectName + "\nSRCS = main.cpp\nOBJS = $(SRCS:.cpp=.o)\n\nall: $(TARGET)\n\n$(TARGET): $(OBJS)\n\t$(CXX) $(CXXFLAGS) -o $@ $^\n\n%.o: %.cpp\n\t$(CXX) $(CXXFLAGS) -c $< -o $@\n\nclean:\n\trm -f $(TARGET) $(OBJS)\n\n.PHONY: all clean\n";
        writeFile(new File(projectDir, "Makefile"), makefile);
        
        // main.cpp
        String mainCpp = "#include <iostream>\n\nint main() {\n    std::cout << \"Hello, " + projectName + "!\" << std::endl;\n    return 0;\n}\n";
        writeFile(new File(projectDir, "main.cpp"), mainCpp);
        
        return true;
    }
    
    private boolean createCMakeTemplate(File projectDir, String projectName) throws IOException {
        // CMakeLists.txt
        String cmake = "cmake_minimum_required(VERSION 3.10)\nproject(" + projectName + ")\n\nset(CMAKE_CXX_STANDARD 17)\nset(CMAKE_CXX_STANDARD_REQUIRED ON)\n\nadd_executable(${PROJECT_NAME} main.cpp)\n";
        writeFile(new File(projectDir, "CMakeLists.txt"), cmake);
        
        // main.cpp
        String mainCpp = "#include <iostream>\n\nint main() {\n    std::cout << \"Hello, " + projectName + "!\" << std::endl;\n    return 0;\n}\n";
        writeFile(new File(projectDir, "main.cpp"), mainCpp);
        
        return true;
    }
    
    private void createReadme(File projectDir, String projectName) {
        try {
            String readme = "# " + projectName + "\n\n## 简介\n\n这是一个使用 Akode IDE 创建的项目。\n\n## 编译\n\n使用 Akode IDE 打开项目并编译运行。\n";
            writeFile(new File(projectDir, "README.md"), readme);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 初始化 Git 仓库
     */
    private void initGitRepository(File projectDir) {
        Git git = null;
        try {
            // 使用 JGit 初始化仓库
            git = Git.init().setDirectory(projectDir).call();
            
            // 创建 .gitignore
            String gitignore = "# 编译输出\n*.o\n*.out\n*.exe\n*.so\n*.a\n\n# IDE 配置\n.akode/\n.idea/\n.vscode/\n\n# 构建目录\nbuild/\nout/\nlibs/\nobj/\n\n# 临时文件\n*.tmp\n*.log\n*.swp\n*~\n";
            writeFile(new File(projectDir, ".gitignore"), gitignore);
            
            // 设置用户信息
            try {
                git.getRepository().getConfig().setString("user", null, "name", "Akode User");
                git.getRepository().getConfig().setString("user", null, "email", "user@akode.local");
                git.getRepository().getConfig().save();
            } catch (Exception configEx) {
                configEx.printStackTrace();
            }
            
            // 不自动 add 和 commit，让用户自己决定
            // 这样进入编辑器后会显示未跟踪的文件
            
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Git 初始化失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            if (git != null) {
                git.close();
            }
        }
    }
    
    private void writeFile(File file, String content) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }
    
    private void showSuccessDialog(String projectPath) {
        new MaterialAlertDialogBuilder(this)
            .setTitle("创建成功")
            .setMessage("项目已创建到:\n" + projectPath)
            .setPositiveButton("打开项目", (dialog, which) -> {
                Intent intent = new Intent(this, EditorActivity.class);
                intent.putExtra("project_path", projectPath);
                startActivity(intent);
                finish();
            })
            .setNegativeButton("关闭", (dialog, which) -> finish())
            .setCancelable(false)
            .show();
    }
}
