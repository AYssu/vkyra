package com.vkyra.code.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.PathParser;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.vkyra.code.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.xmlpull.v1.XmlPullParser;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LauncherDrawableTest {
    @Test public void packagedLauncherPreservesOriginalCompoundContours() throws Exception {
        android.content.Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("launcher-original.path")) {
            byte[] buffer=new byte[8192]; int n; while((n=input.read(buffer))!=-1) bytes.write(buffer,0,n);
        }
        String original=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        Bitmap before=Bitmap.createBitmap(1080,1080,Bitmap.Config.ARGB_8888);
        Bitmap after=Bitmap.createBitmap(1080,1080,Bitmap.Config.ARGB_8888);
        Canvas first=canvas(before), second=canvas(after);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);
        first.drawPath(PathParser.createPathFromPathData(original),paint);
        android.content.res.XmlResourceParser xml=target.getResources().getXml(R.drawable.ic_launcher_foreground);
        int paths=0;
        StringBuilder recovered=new StringBuilder();
        try {
            while(xml.next()!=XmlPullParser.END_DOCUMENT) if(xml.getEventType()==XmlPullParser.START_TAG && "path".equals(xml.getName())) {
                String data=xml.getAttributeValue("http://schemas.android.com/apk/res/android","pathData");
                assertFalse(data.contains("STRING_TOO_LARGE"));
                assertTrue(data.getBytes(StandardCharsets.UTF_8).length<32767);
                recovered.append(data);
                Path path=PathParser.createPathFromPathData(data); assertNotNull(path);
                second.drawPath(path,paint);paths++;
            }
        } finally {xml.close();}
        assertEquals(2,paths);
        assertEquals("Every original command and coordinate must be preserved",original,recovered.toString());
        int[] a=new int[1080*1080],b=new int[a.length];
        before.getPixels(a,0,1080,0,0,1080,1080);after.getPixels(b,0,1080,0,0,1080,1080);
        int different=0,maxAlphaDelta=0,solidDifferences=0;
        for(int i=0;i<a.length;i++) if(a[i]!=b[i]) {
            different++;maxAlphaDelta=Math.max(maxAlphaDelta,Math.abs(Color.alpha(a[i])-Color.alpha(b[i])));
            if(Color.alpha(a[i])==255 || Color.alpha(b[i])==255)solidDifferences++;
        }
        android.util.Log.i("LauncherDrawableTest","different="+different+" maxAlphaDelta="+maxAlphaDelta+" solidDifferences="+solidDifferences);
        // The exact commands above establish geometry preservation. Rasterizing separate paths
        // can round antialias coverage differently from one compound path on newer renderers.
        // Permit only sparse, low-alpha edge differences; a missing contour or hole must fail.
        assertTrue("Launcher edge coverage changed materially: "+maxAlphaDelta,maxAlphaDelta<=12);
        assertTrue("Too many launcher pixels changed: "+different,different<=a.length/2000);
        Drawable drawable=target.getDrawable(R.drawable.ic_launcher_foreground);assertNotNull(drawable);
        drawable.setBounds(0,0,1080,1080);drawable.draw(new Canvas(after));
    }
    private static Canvas canvas(Bitmap bitmap) {
        Canvas canvas=new Canvas(bitmap);canvas.scale(1080f/115.44f,1080f/44.34f);
        canvas.translate(27.384933f,17.694685f);canvas.scale(.52555555f,.2018636f);return canvas;
    }
}
