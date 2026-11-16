package com.vkyra.code.signature;

import com.vkyra.code.home.MainActivity;

import android.graphics.Bitmap;

import net.dongliu.apk.parser.ApkFile;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.value.Entry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 二进制XML编辑器 - 使用ARSCLib解析和修改二进制XML文件
 */
public class BinaryXmlEditor {
    private static final String TAG = "BinaryXmlEditor";
    
    /**
     * 简化的Manifest修改 - 只修改应用名称和版本信息
     */
    public void modifyManifestSimple(File manifestFile, AppInfoDialog.AppInfo appInfo) throws Exception {
        FileLogger.i(TAG, "开始修改二进制AndroidManifest.xml（简化版本）");
        FileLogger.i(TAG, "Manifest文件路径: " + manifestFile.getAbsolutePath());
        FileLogger.i(TAG, "Manifest文件大小: " + manifestFile.length() + " bytes");
        
        try {
            // 直接使用AndroidManifestBlock处理二进制XML文件
            FileLogger.i(TAG, "使用AndroidManifestBlock直接修改二进制XML");
            
            AndroidManifestBlock manifestBlock = AndroidManifestBlock.load(manifestFile);
            
            // 修改版本号
            if (appInfo.versionCode > 0) {
                manifestBlock.setVersionCode(appInfo.versionCode);
                FileLogger.i(TAG, "已修改版本号为: " + appInfo.versionCode);
            }
            
            // 修改版本名称
            if (appInfo.versionInfo != null && !appInfo.versionInfo.trim().isEmpty()) {
                manifestBlock.setVersionName(appInfo.versionInfo);
                FileLogger.i(TAG, "已修改版本名称为: " + appInfo.versionInfo);
            }
            
            // 保存修改后的Manifest
            manifestBlock.writeBytes(manifestFile);
            FileLogger.i(TAG, "二进制AndroidManifest.xml修改完成");
            
        } catch (Exception e) {
            FileLogger.e(TAG, "修改二进制AndroidManifest.xml失败", e);
            FileLogger.w(TAG, "跳过Manifest修改，继续其他处理");
            // 不抛出异常，让流程继续
        }
    }
    
    /**
     * 使用ApkModule方式修改整个APK文件（公共方法）
     */
    public boolean modifyApkWithApkModule(File apkFile, AppInfoDialog.AppInfo appInfo) {
        return modifyManifestWithApkModule(apkFile, appInfo);
    }
    
    /**
     * 使用ApkModule方式修改Manifest（更安全）
     */
    private boolean modifyManifestWithApkModule(File apkFile, AppInfoDialog.AppInfo appInfo) {
        try {
            FileLogger.i(TAG, "开始使用ApkModule修改APK文件");
            FileLogger.i(TAG, "APK文件路径: " + apkFile.getAbsolutePath());
            FileLogger.i(TAG, "APK文件大小: " + apkFile.length() + " bytes");
            FileLogger.i(TAG, "APK文件存在: " + apkFile.exists());
            FileLogger.i(TAG, "APK文件可读: " + apkFile.canRead());
            
            // 检查文件是否为空或过小
            if (apkFile.length() < 1024) {
                FileLogger.e(TAG, "APK文件过小，可能损坏: " + apkFile.length() + " bytes");
                return false;
            }
            
            // 检查ZIP文件头
            if (!isValidZipFile(apkFile)) {
                FileLogger.e(TAG, "APK文件不是有效的ZIP格式");
                return false;
            }
            
            // 创建APK文件的父目录副本来处理
            File parentDir = apkFile.getParentFile();
            File tempApkFile = new File(parentDir, "temp_" + apkFile.getName());
            
            // 复制原文件到临时文件
            java.nio.file.Files.copy(apkFile.toPath(), tempApkFile.toPath(), 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            FileLogger.i(TAG, "临时文件创建成功: " + tempApkFile.getAbsolutePath());
            FileLogger.i(TAG, "临时文件大小: " + tempApkFile.length() + " bytes");
            
            // 加载APK模块
            try (ApkModule apkModule = ApkModule.loadApkFile(tempApkFile)) {
            
            // 获取AndroidManifest.xml
            AndroidManifestBlock manifest = apkModule.getAndroidManifest();
            
            if (manifest == null) {
                FileLogger.e(TAG, "未找到AndroidManifest.xml");
                tempApkFile.delete();
                return false;
            }

            // 修改包名（仅修改 manifest/package 及相关引用；不改 Activity/Service 的类名）
            String oldPackageName = manifest.getPackageName();
            String newPackageName = appInfo != null ? appInfo.packageName : null;
            if (newPackageName != null) {
                newPackageName = newPackageName.trim();
            }
            if (newPackageName != null && !newPackageName.isEmpty()
                    && oldPackageName != null && !oldPackageName.isEmpty()
                    && !newPackageName.equals(oldPackageName)) {
                manifest.setPackageName(newPackageName);
                FileLogger.i(TAG, "已修改Manifest包名: " + oldPackageName + " -> " + newPackageName);
                updatePermissions(manifest, oldPackageName, newPackageName);
            }
            
            // 修改版本号
            if (appInfo.versionCode > 0) {
                manifest.setVersionCode(appInfo.versionCode);
                FileLogger.i(TAG, "已修改版本号为: " + appInfo.versionCode);
            }
            
            // 修改版本名称
            if (appInfo.versionInfo != null && !appInfo.versionInfo.isEmpty()) {
                manifest.setVersionName(appInfo.versionInfo);
                FileLogger.i(TAG, "已修改版本名称为: " + appInfo.versionInfo);
            }
            
            // 修改应用名称（通过资源表）
            if (appInfo.appName != null && !appInfo.appName.isEmpty()) {
                TableBlock tableBlock = apkModule.getTableBlock();
                if (tableBlock != null) {
                    PackageBlock packageBlock = tableBlock.pickOne();
                    if (packageBlock != null) {
                        try {
                            Entry appNameEntry = packageBlock.getOrCreate("", "string", "app_name");
                            if (appNameEntry != null) {
                                appNameEntry.setValueAsString(appInfo.appName);
                                FileLogger.i(TAG, "已修改app_name为: " + appInfo.appName);
                            }
                        } catch (Exception e) {
                            throw new IllegalStateException("Cannot update application name", e);
                        }
                    }
                }
            }
            
            // 保存修改到临时文件
            apkModule.writeApk(tempApkFile);

            // 替换图标资源：优先读取 manifest 的 icon/roundIcon 引用并解析到真实 res 路径
            if (appInfo != null && appInfo.icon != null) {
                try {
                    Set<String> iconPaths = resolveIconPathsFromManifest(manifest, apkModule.getTableBlock());
                    if (iconPaths.isEmpty()) {
                        iconPaths.addAll(resolveIconPathsWithApkParser(tempApkFile));
                    }
                    replaceLauncherIconsInApk(tempApkFile, appInfo.icon, iconPaths);
                    FileLogger.i(TAG, "图标资源替换完成");
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot update application icon", e);
                }
            }
            
            // 将临时文件复制回原文件
            java.nio.file.Files.copy(tempApkFile.toPath(), apkFile.toPath(), 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            // 删除临时文件
            tempApkFile.delete();
            
            FileLogger.i(TAG, "ApkModule方式修改成功");
            return true;
            }
            
        } catch (Exception e) {
            FileLogger.e(TAG, "ApkModule方式修改失败", e);
            return false;
        }
    }

    private void replaceLauncherIconsInApk(File apkFile, Bitmap icon, Set<String> manifestResolvedIconPaths) throws Exception {
        if (icon == null) return;
        FileLogger.i(TAG, "已找到图标资源，准备替换:" + manifestResolvedIconPaths);
        if (manifestResolvedIconPaths == null || manifestResolvedIconPaths.isEmpty()) {
            FileLogger.i(TAG, "Manifest未解析到图标路径，按要求不进行图标替换");
            return;
        }

        try (ZipFile zipFile = new ZipFile(apkFile)) {
        int replaced = 0;

        for (String rawPath : manifestResolvedIconPaths) {
            String path = normalizeResPath(rawPath);
            if (path.isEmpty()) continue;

            FileHeader fileHeader = zipFile.getFileHeader(path);
            if (fileHeader == null) {
                FileLogger.w(TAG, "APK中未找到图标条目: " + path);
                continue;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            boolean ok = icon.compress(Bitmap.CompressFormat.PNG, 100, baos);
            if (!ok) {
                FileLogger.w(TAG, "图标编码失败，跳过条目: " + path);
                continue;
            }

            ZipParameters parameters = new ZipParameters();
            parameters.setFileNameInZip(path);
            parameters.setOverrideExistingFilesInZip(true);
            // PNG 默认使用 STORE，避免无意义压缩
            parameters.setCompressionMethod(net.lingala.zip4j.model.enums.CompressionMethod.STORE);

            zipFile.addStream(new ByteArrayInputStream(baos.toByteArray()), parameters);
            replaced++;
            FileLogger.i(TAG, "已替换图标条目: " + path);
        }

        if (replaced <= 0) {
            throw new Exception("未找到可替换的图标文件（Manifest解析到的路径在APK中不存在）");
        }

        FileLogger.i(TAG, "已替换图标文件数量: " + replaced);
        }
    }

    private boolean isManifestResolvedIconPath(String entryName, Set<String> manifestResolvedIconPaths) {
        if (entryName == null || manifestResolvedIconPaths == null || manifestResolvedIconPaths.isEmpty()) {
            return false;
        }
        String normalized = normalizeResPath(entryName);
        return manifestResolvedIconPaths.contains(normalized);
    }

    /**
     * 从 AndroidManifest 的 application/icon 与 roundIcon 读取引用，并尽量解析到 APK 内真实 res 路径。
     */
    private Set<String> resolveIconPathsFromManifest(AndroidManifestBlock manifest, TableBlock tableBlock) {
        Set<String> results = new LinkedHashSet<>();
        if (manifest == null) return results;

        try {
            com.reandroid.arsc.chunk.xml.ResXmlElement manifestElement = manifest.getManifestElement();
            if (manifestElement == null) return results;
            debugLogManifestElement(manifest, manifestElement);

            for (com.reandroid.arsc.chunk.xml.ResXmlElement appElement : manifestElement.listElements("application")) {
                com.reandroid.arsc.chunk.xml.ResXmlAttribute iconAttr = findApplicationIconAttribute(appElement, true);
                com.reandroid.arsc.chunk.xml.ResXmlAttribute roundIconAttr = findApplicationIconAttribute(appElement, false);
                collectPathsFromIconAttribute(iconAttr, tableBlock, results);
                collectPathsFromIconAttribute(roundIconAttr, tableBlock, results);
            }
        } catch (Exception e) {
            FileLogger.w(TAG, "读取Manifest图标引用失败", e);
        }

        if (!results.isEmpty()) {
            FileLogger.i(TAG, "从Manifest解析到图标路径: " + results);
        } else {
            FileLogger.w(TAG, "未从Manifest解析到图标路径，按要求不进行替换");
        }
        return results;
    }

    /**
     * 打印 manifestElement 的关键解析结果，便于定位 icon/roundIcon 属性读取问题。
     */
    private void debugLogManifestElement(
            AndroidManifestBlock manifest,
            com.reandroid.arsc.chunk.xml.ResXmlElement manifestElement) {
        try {
            FileLogger.i(TAG, "manifest.package = " + manifest.getPackageName());
        } catch (Exception e) {
            FileLogger.w(TAG, "打印manifest.package失败", e);
        }

        try {
            java.util.List<com.reandroid.arsc.chunk.xml.ResXmlElement> apps =
                manifestElement.listElements("application");
            FileLogger.i(TAG, "manifestElement.applicationCount = " + (apps == null ? 0 : apps.size()));
            if (apps == null) return;

            int index = 0;
            for (com.reandroid.arsc.chunk.xml.ResXmlElement appElement : apps) {
                FileLogger.i(TAG, "application[" + index + "] attributes begin");
                debugLogElementAttributes(appElement, "application[" + index + "]");
                FileLogger.i(TAG, "application[" + index + "] attributes end");
                index++;
            }
        } catch (Exception e) {
            FileLogger.w(TAG, "打印application节点失败", e);
        }
    }

    /**
     * 使用反射枚举元素属性，避免依赖 ARSCLib 具体版本 API。
     */
    private void debugLogElementAttributes(
            com.reandroid.arsc.chunk.xml.ResXmlElement element,
            String elementTagForLog) {
        if (element == null) return;
        try {
            java.lang.reflect.Method listAttributesMethod = element.getClass().getMethod("listAttributes");

            Object attrsObj = listAttributesMethod.invoke(element);
            if (!(attrsObj instanceof Iterable)) {
                FileLogger.w(TAG, elementTagForLog + " 属性列表不可迭代");
                return;
            }

            int count = 0;
            for (Object attrObj : (Iterable<?>) attrsObj) {
                if (attrObj == null) continue;
                String name = safeInvokeString(attrObj, "getName");
                String value = safeInvokeString(attrObj, "getValueAsString");
                String data = safeInvokeString(attrObj, "getData");
                FileLogger.i(TAG, elementTagForLog + " attr: name=" + name + ", value=" + value + ", data=" + data);
                count++;
            }
            FileLogger.i(TAG, elementTagForLog + " attrCount=" + count);
        } catch (NoSuchMethodException e) {
            FileLogger.w(TAG, elementTagForLog + " 当前库无listAttributes方法，无法完整枚举属性");
        } catch (Exception e) {
            FileLogger.w(TAG, elementTagForLog + " 枚举属性失败", e);
        }
    }

    private String safeInvokeString(Object target, String methodName) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return String.valueOf(v);
        } catch (Exception ignored) {
            return "N/A";
        }
    }

    private int safeInvokeInt(Object target, String methodName) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof Number) {
                return ((Number) v).intValue();
            }
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Object invokeFirstAvailable(Object target, String[] methodNames, Object... args) {
        if (target == null || methodNames == null) return null;
        for (String name : methodNames) {
            try {
                java.lang.reflect.Method[] methods = target.getClass().getMethods();
                for (java.lang.reflect.Method m : methods) {
                    if (!m.getName().equals(name)) continue;
                    if (m.getParameterTypes().length != args.length) continue;
                    return m.invoke(target, args);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v == null) continue;
            String s = v.trim();
            if (!s.isEmpty() && !"N/A".equals(s)) {
                return s;
            }
        }
        return null;
    }

    private void collectPathsFromIconAttribute(
            com.reandroid.arsc.chunk.xml.ResXmlAttribute attr,
            TableBlock tableBlock,
            Set<String> outPaths) {
        if (attr == null || outPaths == null) return;

        String raw = firstNonEmpty(
            safeInvokeString(attr, "getValueAsString"),
            safeInvokeString(attr, "getValueAsReference"),
            safeInvokeString(attr, "getValueAsRawString")
        );
        if (raw == null) raw = "";
        raw = raw.trim();
        int dataInt = safeInvokeInt(attr, "getData");
        FileLogger.i(TAG, "解析图标属性值: " + raw + ", data=0x" + Integer.toHexString(dataInt));

        // 1) 直接就是资源文件路径，例如 res/9T.png
        if (raw.startsWith("res/") && raw.toLowerCase(Locale.ROOT).endsWith(".png")) {
            outPaths.add(normalizeResPath(raw));
            return;
        }

        // 2) 形如 @drawable/icon 或 @mipmap/ic_launcher
        // 尝试通过资源表解析该条目得到真实文件路径（混淆后通常是 res/xx.png）
        int slash = raw.indexOf('/');
        if (raw.startsWith("@") && slash > 1 && slash < raw.length() - 1) {
            String typePart = raw.substring(1, slash);
            int colon = typePart.indexOf(':');
            if (colon >= 0 && colon < typePart.length() - 1) {
                typePart = typePart.substring(colon + 1);
            }
            String namePart = raw.substring(slash + 1);
            String resolved = resolveResPathByTypeAndName(tableBlock, typePart, namePart);
            if (resolved != null) {
                outPaths.add(normalizeResPath(resolved));
                return;
            }
        }

        // 3) 字符串为空或不可解析时，尝试按资源ID反查（常见于编译后的 manifest 引用）
        if (dataInt != 0) {
            String byId = resolveResPathByResourceId(tableBlock, dataInt);
            if (byId != null) {
                outPaths.add(normalizeResPath(byId));
                FileLogger.i(TAG, "通过资源ID解析图标路径成功: 0x"
                    + Integer.toHexString(dataInt) + " -> " + byId);
            } else {
                FileLogger.w(TAG, "通过资源ID解析图标路径失败: 0x" + Integer.toHexString(dataInt));
            }
        }
    }

    private com.reandroid.arsc.chunk.xml.ResXmlAttribute findApplicationIconAttribute(
            com.reandroid.arsc.chunk.xml.ResXmlElement appElement,
            boolean roundIcon) {
        if (appElement == null) return null;
        String[] candidates = roundIcon
            ? new String[]{"android:roundIcon", "roundIcon"}
            : new String[]{"android:icon", "icon"};
        for (String name : candidates) {
            try {
                com.reandroid.arsc.chunk.xml.ResXmlAttribute attr = appElement.searchAttributeByName(name);
                if (attr != null) {
                    FileLogger.i(TAG, "命中application属性: " + name);
                    return attr;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 通过 TableBlock/PackageBlock 尝试把 (type,name) 解析成实际资源文件路径（如 res/9T.png）。
     * 为降低对 ARSCLib 版本的耦合，这里使用反射读取 Entry 的字符串值。
     */
    private String resolveResPathByTypeAndName(TableBlock tableBlock, String type, String name) {
        if (tableBlock == null || type == null || name == null || type.isEmpty() || name.isEmpty()) {
            return null;
        }
        try {
            PackageBlock pkg = tableBlock.pickOne();
            if (pkg == null) return null;

            Entry entry = pkg.getOrCreate("", type, name);
            if (entry == null) return null;

            java.lang.reflect.Method getter = entry.getClass().getMethod("getValueAsString");
            Object value = getter.invoke(entry);
            if (!(value instanceof String)) return null;

            String path = ((String) value).trim();
            if (path.startsWith("res/") && path.toLowerCase(Locale.ROOT).endsWith(".png")) {
                return path;
            }
        } catch (Exception e) {
            FileLogger.w(TAG, "解析图标资源路径失败: " + type + "/" + name, e);
        }
        return null;
    }

    /**
     * 按资源ID反查真实文件路径（如 res/9T.png）。
     * 通过反射适配 ARSCLib 不同版本 API。
     */
    private String resolveResPathByResourceId(TableBlock tableBlock, int resId) {
        if (tableBlock == null || resId == 0) return null;
        try {
            PackageBlock pkg = tableBlock.pickOne();
            if (pkg == null) return null;

            Object entryGroup = invokeFirstAvailable(pkg, new String[]{"getEntryGroup", "searchEntryGroup"}, resId);
            if (entryGroup == null) {
                entryGroup = invokeFirstAvailable(tableBlock, new String[]{"getEntryGroup", "searchEntryGroup"}, resId);
            }
            if (entryGroup == null) return null;

            Entry entry = null;
            Object picked = invokeFirstAvailable(entryGroup, new String[]{"pickOne", "getEntry", "getBestEntry"});
            if (picked instanceof Entry) {
                entry = (Entry) picked;
            } else if (entryGroup instanceof Entry) {
                entry = (Entry) entryGroup;
            }
            if (entry == null) return null;

            String path = safeInvokeString(entry, "getValueAsString");
            if (path != null) {
                path = path.trim();
            }
            if (path != null && path.startsWith("res/") && path.toLowerCase(Locale.ROOT).endsWith(".png")) {
                return path;
            }
        } catch (Exception e) {
            FileLogger.w(TAG, "资源ID反查失败: 0x" + Integer.toHexString(resId), e);
        }
        return null;
    }

    private String normalizeResPath(String path) {
        if (path == null) return "";
        return path.replace('\\', '/');
    }

    /**
     * 使用 apk-parser 直接解析 icon 文件条目路径（如 res/9T.png）。
     */
    private Set<String> resolveIconPathsWithApkParser(File apkFile) {
        Set<String> paths = new LinkedHashSet<>();
        if (apkFile == null || !apkFile.exists()) return paths;

        try (ApkFile parser = new ApkFile(apkFile)) {
            Object iconFile = parser.getIconFile();
            if (iconFile == null) {
                FileLogger.w(TAG, "apk-parser 未返回 iconFile");
                return paths;
            }

            String[] methodCandidates = new String[]{"getPath", "getName", "getFileName"};
            for (String method : methodCandidates) {
                String value = safeInvokeString(iconFile, method);
                if (value != null && !"N/A".equals(value)) {
                    value = value.trim();
                    if (value.startsWith("res/") && value.toLowerCase(Locale.ROOT).endsWith(".png")) {
                        paths.add(normalizeResPath(value));
                        FileLogger.i(TAG, "apk-parser解析到图标路径(" + method + "): " + value);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            FileLogger.w(TAG, "apk-parser解析图标路径失败", e);
        }
        return paths;
    }
    
    /**
     * 检查文件是否为有效的ZIP格式
     */
    private boolean isValidZipFile(File file) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] header = new byte[4];
            int bytesRead = fis.read(header);
            fis.close();
            
            if (bytesRead < 4) {
                FileLogger.e(TAG, "文件头读取失败，文件可能损坏");
                return false;
            }
            
            // 检查ZIP文件头 (PK\003\004 或 PK\005\006 或 PK\007\008)
            boolean isZip = (header[0] == 0x50 && header[1] == 0x4B) && 
                           ((header[2] == 0x03 && header[3] == 0x04) ||  // 本地文件头
                            (header[2] == 0x05 && header[3] == 0x06) ||  // 中央目录结束记录
                            (header[2] == 0x07 && header[3] == 0x08));   // 数据描述符
            
            FileLogger.i(TAG, "ZIP文件头检查: " + 
                String.format("0x%02X%02X%02X%02X", header[0], header[1], header[2], header[3]) + 
                " (有效: " + isZip + ")");
            
            return isZip;
            
        } catch (Exception e) {
            FileLogger.e(TAG, "ZIP文件头检查失败", e);
            return false;
        }
    }
    
    /**
     * 修改APK文件中的应用名称
     */
    public static boolean modifyAppName(File apkFile, String newAppName) {
        try {
            FileLogger.i(TAG, "开始修改APK文件中的应用名称: " + newAppName);
            FileLogger.i(TAG, "APK文件: " + apkFile.getAbsolutePath());
            
            // 加载APK模块
            ApkModule apkModule = ApkModule.loadApkFile(apkFile);
            
            // 获取资源表
            TableBlock tableBlock = apkModule.getTableBlock();
            if (tableBlock == null) {
                FileLogger.e(TAG, "未找到资源表");
                return false;
            }
            
            // 获取第一个PackageBlock
            PackageBlock packageBlock = tableBlock.pickOne();
            if (packageBlock == null) {
                FileLogger.e(TAG, "未找到PackageBlock");
                return false;
            }
            
            FileLogger.i(TAG, "找到包: " + packageBlock.getName());
            
            // 使用getOrCreate获取app_name资源
            Entry appNameEntry = packageBlock.getOrCreate("", "string", "app_name");
            if (appNameEntry != null) {
                appNameEntry.setValueAsString(newAppName);
                FileLogger.i(TAG, "已修改app_name为: " + newAppName);
                
                // 保存修改到APK文件
                apkModule.writeApk(apkFile);
                FileLogger.i(TAG, "应用名称修改成功");
                return true;
            } else {
                FileLogger.w(TAG, "无法获取或创建app_name资源");
                return false;
            }
            
        } catch (Exception e) {
            FileLogger.e(TAG, "修改应用名称失败", e);
            return false;
        }
    }
    
    /**
     * 修改APK文件中的包名和版本信息
     */
    public static boolean modifyManifest(File apkFile, String packageName, 
                                        String versionCode, String versionName) {
        try {
            FileLogger.i(TAG, "开始修改APK文件中的Manifest信息");
            FileLogger.i(TAG, "APK文件: " + apkFile.getAbsolutePath());
            FileLogger.i(TAG, "包名: " + packageName);
            FileLogger.i(TAG, "版本号: " + versionCode);
            FileLogger.i(TAG, "版本名称: " + versionName);
            
            // 加载APK模块
            ApkModule apkModule = ApkModule.loadApkFile(apkFile);
            
            // 获取AndroidManifest.xml
            AndroidManifestBlock manifest = apkModule.getAndroidManifest();
            if (manifest == null) {
                FileLogger.e(TAG, "未找到AndroidManifest.xml");
                return false;
            }
            
            // 获取原包名
            String oldPackageName = manifest.getPackageName();
            FileLogger.i(TAG, "原包名: " + oldPackageName);
            
            // 修改包名
            if (packageName != null && !packageName.isEmpty() && !packageName.equals(oldPackageName)) {
                // 修改Manifest中的包名
                manifest.setPackageName(packageName);
                FileLogger.i(TAG, "已修改Manifest包名为: " + packageName);
                
                // 同时修改PackageBlock中的包名
                TableBlock tableBlock = apkModule.getTableBlock();
                if (tableBlock != null) {
                    PackageBlock packageBlock = tableBlock.pickOne();
                    if (packageBlock != null) {
                        packageBlock.setName(packageName);
                        FileLogger.i(TAG, "已修改PackageBlock包名: " + oldPackageName + " -> " + packageName);
                    }
                }
                
                // 修改所有使用旧包名的权限声明
                if (oldPackageName != null && !oldPackageName.isEmpty()) {
                    updatePermissions(manifest, oldPackageName, packageName);
                }
            }
            
            // 修改版本号
            if (versionCode != null && !versionCode.isEmpty()) {
                try {
                    int versionCodeInt = Integer.parseInt(versionCode);
                    manifest.setVersionCode(versionCodeInt);
                    FileLogger.i(TAG, "已修改版本号为: " + versionCode);
                } catch (NumberFormatException e) {
                    FileLogger.w(TAG, "版本号格式错误: " + versionCode);
                }
            }
            
            // 修改版本名称
            if (versionName != null && !versionName.isEmpty()) {
                manifest.setVersionName(versionName);
                FileLogger.i(TAG, "已修改版本名称为: " + versionName);
            }
            
            // 保存修改到APK文件
            apkModule.writeApk(apkFile);
            FileLogger.i(TAG, "Manifest修改成功");
            return true;
            
        } catch (Exception e) {
            FileLogger.e(TAG, "修改Manifest失败", e);
            return false;
        }
    }
    
    /**
     * 更新AndroidManifest.xml中的权限声明和provider，将旧包名替换为新包名
     */
    private static void updatePermissions(AndroidManifestBlock manifest, String oldPackageName, String newPackageName) {
        try {
            FileLogger.i(TAG, "开始更新权限声明和provider");
            
            // 获取manifest的根元素
            com.reandroid.arsc.chunk.xml.ResXmlElement manifestElement = manifest.getManifestElement();
            if (manifestElement == null) {
                FileLogger.w(TAG, "无法获取manifest元素");
                return;
            }
            
            int updateCount = 0;
            
            // 处理permission标签
            for (com.reandroid.arsc.chunk.xml.ResXmlElement child : manifestElement.listElements("permission")) {
                com.reandroid.arsc.chunk.xml.ResXmlAttribute nameAttr = 
                    child.searchAttributeByName("android:name");
                if (nameAttr != null) {
                    String permissionName = nameAttr.getValueAsString();
                    if (permissionName != null && permissionName.startsWith(oldPackageName)) {
                        String newPermissionName = permissionName.replace(oldPackageName, newPackageName);
                        nameAttr.setValueAsString(newPermissionName);
                        FileLogger.i(TAG, "已更新permission: " + permissionName + " -> " + newPermissionName);
                        updateCount++;
                    }
                }
            }
            
            // 处理uses-permission标签
            for (com.reandroid.arsc.chunk.xml.ResXmlElement child : manifestElement.listElements("uses-permission")) {
                com.reandroid.arsc.chunk.xml.ResXmlAttribute nameAttr = 
                    child.searchAttributeByName("android:name");
                if (nameAttr != null) {
                    String permissionName = nameAttr.getValueAsString();
                    if (permissionName != null && permissionName.startsWith(oldPackageName)) {
                        String newPermissionName = permissionName.replace(oldPackageName, newPackageName);
                        nameAttr.setValueAsString(newPermissionName);
                        FileLogger.i(TAG, "已更新uses-permission: " + permissionName + " -> " + newPermissionName);
                        updateCount++;
                    }
                }
            }
            
            // 处理application下的provider标签
            for (com.reandroid.arsc.chunk.xml.ResXmlElement appElement : manifestElement.listElements("application")) {
                for (com.reandroid.arsc.chunk.xml.ResXmlElement provider : appElement.listElements("provider")) {
                    // 更新authorities属性
                    com.reandroid.arsc.chunk.xml.ResXmlAttribute authoritiesAttr = 
                        provider.searchAttributeByName("android:authorities");
                    if (authoritiesAttr != null) {
                        String authorities = authoritiesAttr.getValueAsString();
                        if (authorities != null && authorities.startsWith(oldPackageName)) {
                            String newAuthorities = authorities.replace(oldPackageName, newPackageName);
                            authoritiesAttr.setValueAsString(newAuthorities);
                            FileLogger.i(TAG, "已更新provider authorities: " + authorities + " -> " + newAuthorities);
                            updateCount++;
                        }
                    }
                    
                    // 更新name属性（如果使用相对路径）
                    com.reandroid.arsc.chunk.xml.ResXmlAttribute nameAttr = 
                        provider.searchAttributeByName("android:name");
                    if (nameAttr != null) {
                        String providerName = nameAttr.getValueAsString();
                        if (providerName != null && providerName.startsWith(oldPackageName)) {
                            String newProviderName = providerName.replace(oldPackageName, newPackageName);
                            nameAttr.setValueAsString(newProviderName);
                            FileLogger.i(TAG, "已更新provider name: " + providerName + " -> " + newProviderName);
                            updateCount++;
                        }
                    }
                }
                
                // 处理其他可能包含包名的组件
                // 按需求：不修改 Activity/Service 的 name（避免改动组件类名）
                updateComponentNames(appElement, "receiver", oldPackageName, newPackageName);
            }
            
            FileLogger.i(TAG, "权限和provider更新完成，共更新 " + updateCount + " 个");
            
        } catch (Exception e) {
            FileLogger.e(TAG, "更新权限和provider失败", e);
        }
    }
    
    /**
     * 更新组件名称中的包名
     */
    private static void updateComponentNames(com.reandroid.arsc.chunk.xml.ResXmlElement appElement, 
                                           String componentType, String oldPackageName, String newPackageName) {
        try {
            for (com.reandroid.arsc.chunk.xml.ResXmlElement component : appElement.listElements(componentType)) {
                com.reandroid.arsc.chunk.xml.ResXmlAttribute nameAttr = 
                    component.searchAttributeByName("android:name");
                if (nameAttr != null) {
                    String componentName = nameAttr.getValueAsString();
                    if (componentName != null) {
                        // 如果是完整类名，转换为相对路径
                        if (componentName.startsWith(oldPackageName + ".")) {
                            String className = componentName.substring(oldPackageName.length());
                            nameAttr.setValueAsString(className); // 使用相对路径，如 ".MainActivity"
                            FileLogger.i(TAG, "已更新" + componentType + " name为相对路径: " + componentName + " -> " + className);
                        }
                        // 如果已经是相对路径，保持不变
                        else if (componentName.startsWith(".")) {
                            FileLogger.i(TAG, componentType + " name已是相对路径: " + componentName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLogger.e(TAG, "更新" + componentType + "名称失败", e);
        }
    }
    
    /**
     * 修改APK文件中的字符串资源
     */
    public static boolean modifyStringResource(File apkFile, String resourceName, String newValue) {
        try {
            FileLogger.i(TAG, "开始修改APK文件中的字符串资源: " + resourceName + " -> " + newValue);
            FileLogger.i(TAG, "APK文件: " + apkFile.getAbsolutePath());
            
            // 加载APK模块
            ApkModule apkModule = ApkModule.loadApkFile(apkFile);
            
            // 获取资源表
            TableBlock tableBlock = apkModule.getTableBlock();
            if (tableBlock == null) {
                FileLogger.e(TAG, "未找到资源表");
                return false;
            }
            
            // 获取第一个PackageBlock
            PackageBlock packageBlock = tableBlock.pickOne();
            if (packageBlock == null) {
                FileLogger.e(TAG, "未找到PackageBlock");
                return false;
            }
            
            FileLogger.i(TAG, "找到包: " + packageBlock.getName());
            
            // 使用getOrCreate获取指定的字符串资源
            Entry entry = packageBlock.getOrCreate("", "string", resourceName);
            if (entry != null) {
                entry.setValueAsString(newValue);
                FileLogger.i(TAG, "已修改" + resourceName + "为: " + newValue);
                
                // 保存修改到APK文件
                apkModule.writeApk(apkFile);
                FileLogger.i(TAG, "字符串资源修改成功");
                return true;
            } else {
                FileLogger.w(TAG, "无法获取或创建字符串资源: " + resourceName);
                return false;
            }
            
        } catch (Exception e) {
            FileLogger.e(TAG, "修改字符串资源失败", e);
            return false;
        }
    }
}