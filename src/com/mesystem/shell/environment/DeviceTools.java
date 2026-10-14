package com.mesystem.shell;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

final class DeviceTools {
    private final Context context;
    private final UsbManager usb;
    DeviceTools(Context context) { this.context=context; usb=context.getSystemService(UsbManager.class); }
    JSONArray usbDevices() throws Exception {
        JSONArray result=new JSONArray();
        for(UsbDevice device:usb.getDeviceList().values()) {
            JSONObject info=new JSONObject().put("name",device.getDeviceName()).put("vendor_id",device.getVendorId())
                .put("product_id",device.getProductId()).put("product",device.getProductName()).put("permission",usb.hasPermission(device));
            JSONArray interfaces=new JSONArray();
            for(int i=0;i<device.getInterfaceCount();i++) {
                UsbInterface intf=device.getInterface(i); JSONArray endpoints=new JSONArray();
                for(int j=0;j<intf.getEndpointCount();j++) {
                    UsbEndpoint endpoint=intf.getEndpoint(j);
                    endpoints.put(new JSONObject().put("index",j).put("address",endpoint.getAddress()).put("direction",endpoint.getDirection()).put("type",endpoint.getType()));
                }
                interfaces.put(new JSONObject().put("index",i).put("class",intf.getInterfaceClass()).put("endpoints",endpoints));
            }
            result.put(info.put("interfaces",interfaces));
        }
        return result;
    }
    JSONObject transfer(JSONObject args,OpenAiClient.Cancel cancel) throws Exception {
        UsbDevice device=usb.getDeviceList().get(args.getString("device"));
        if(device==null) throw new IllegalArgumentException(Language.text(R.string.usb_device_not_connected));
        if(!usb.hasPermission(device)) {
            PendingIntent intent=PendingIntent.getBroadcast(context,device.getDeviceId(),
                new Intent(context,UsbPermissionReceiver.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            usb.requestPermission(device,intent);
            long end=System.currentTimeMillis()+45000;
            while(!usb.hasPermission(device) && System.currentTimeMillis()<end) { cancel.check(); Thread.sleep(200); }
            if(!usb.hasPermission(device)) return new JSONObject().put("error",Language.text(R.string.android_usb_permission_denied));
        }
        UsbDeviceConnection connection=usb.openDevice(device);
        if(connection==null) throw new IllegalStateException(Language.text(R.string.cannot_open_usb));
        try {
            byte[] data=args.has("data_base64")?Base64.decode(args.getString("data_base64"),Base64.DEFAULT):new byte[Math.max(0,Math.min(65536,args.optInt("length",64)))];
            if(data.length>65536) throw new IllegalArgumentException("USB transfer limit is 64 KiB");
            int timeout=Math.max(100,Math.min(5000,args.optInt("timeout_ms",1000))),count;
            if("control".equals(args.optString("kind","control"))) {
                count=connection.controlTransfer(args.getInt("request_type"),args.getInt("request"),args.optInt("value"),args.optInt("index"),data,data.length,timeout);
            } else {
                UsbInterface intf=device.getInterface(args.getInt("interface")); UsbEndpoint endpoint=intf.getEndpoint(args.getInt("endpoint"));
                if(!connection.claimInterface(intf,false)) throw new IllegalStateException("USB interface is busy");
                try { count=connection.bulkTransfer(endpoint,data,data.length,timeout); } finally { connection.releaseInterface(intf); }
            }
            return new JSONObject().put("transferred",count).put("data_base64",count>0?Base64.encodeToString(data,0,count,Base64.NO_WRAP):"");
        } finally { connection.close(); }
    }
}
