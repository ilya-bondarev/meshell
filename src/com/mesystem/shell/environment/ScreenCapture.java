package com.mesystem.shell;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.ByteArrayOutputStream;

public final class ScreenCapture {
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("screen path required");
        Bitmap source=BitmapFactory.decodeFile(args[0]);
        if(source==null) throw new IllegalArgumentException("Cannot decode screenshot");
        int width=Math.min(900,source.getWidth());
        Bitmap scaled=Bitmap.createScaledBitmap(source,width,Math.max(1,source.getHeight()*width/source.getWidth()),true);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG,65,out);
        if(out.size()>280000) { out.reset(); scaled.compress(Bitmap.CompressFormat.JPEG,35,out); }
        if(out.size()>360000) throw new IllegalArgumentException("Screenshot is too large");
        System.out.println("MESHELL_IMAGE:"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP));
        if(scaled!=source) scaled.recycle(); source.recycle();
    }
}
