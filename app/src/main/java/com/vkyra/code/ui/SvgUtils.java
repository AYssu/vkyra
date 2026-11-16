package com.vkyra.code.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.content.Context;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

/**
 * SVG 工具类
 * 用于解析和渲染 SVG 字符串
 */
public class SvgUtils {
    
    /**
     * 将 SVG 字符串渲染为 Drawable
     * @param context 上下文
     * @param svgString SVG 字符串
     * @param sizeDp 目标尺寸（dp）
     * @return Drawable，失败返回 null
     */
    public static Drawable svgToDrawable(Context context, String svgString, int sizeDp) {
        if (svgString == null || svgString.isEmpty()) return null;
        
        try {
            SVG svg = SVG.getFromString(svgString);
            
            // 计算像素尺寸
            float density = context.getResources().getDisplayMetrics().density;
            int sizePx = (int) (sizeDp * density);
            
            // 设置文档尺寸
            svg.setDocumentWidth(sizePx);
            svg.setDocumentHeight(sizePx);
            
            // 创建 Bitmap
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            svg.renderToCanvas(canvas);
            
            return new BitmapDrawable(context.getResources(), bitmap);
        } catch (SVGParseException e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 将 Base64 编码的 SVG 渲染为 Drawable
     * @param context 上下文
     * @param base64Svg Base64 编码的 SVG 字符串
     * @param sizeDp 目标尺寸（dp）
     * @return Drawable，失败返回 null
     */
    public static Drawable base64SvgToDrawable(Context context, String base64Svg, int sizeDp) {
        if (base64Svg == null || base64Svg.isEmpty()) return null;
        
        try {
            byte[] decoded = android.util.Base64.decode(base64Svg, android.util.Base64.DEFAULT);
            String svgString = new String(decoded, "UTF-8");
            return svgToDrawable(context, svgString, sizeDp);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
