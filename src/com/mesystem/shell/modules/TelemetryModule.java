package com.mesystem.shell;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import org.json.JSONArray;
import org.json.JSONObject;

final class TelemetryModule {
    private final Context context;
    private final ShellBroker shell;
    private long previousTotal,previousIdle,previousRx,previousTx,previousTime;
    TelemetryModule(Context context,ShellBroker shell) { this.context=context; this.shell=shell; }
    synchronized JSONObject sample(OpenAiClient.Cancel cancel) throws Exception {
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo(); context.getSystemService(ActivityManager.class).getMemoryInfo(memory);
        JSONObject result=new JSONObject().put("ram_total",memory.totalMem).put("ram_available",memory.availMem)
            .put("ram_used_percent",100.0*(memory.totalMem-memory.availMem)/memory.totalMem).put("cores",Runtime.getRuntime().availableProcessors());
        try {
            ShellBroker.Result cpu=shell.run("head -n 1 /proc/stat",3000,cancel);
            String[] fields=cpu.output.trim().split("\\s+");
            if(cpu.exitCode!=0 || fields.length<5 || !fields[0].equals("cpu")) throw new IllegalStateException(Language.text(R.string.cannot_access_proc_stat));
            long total=0; for(int i=1;i<Math.min(9,fields.length);i++) total+=Long.parseLong(fields[i]);
            long idle=Long.parseLong(fields[4])+(fields.length>5?Long.parseLong(fields[5]):0);
            if(previousTotal>0 && total>previousTotal) result.put("cpu_percent",Math.max(0,Math.min(100,100.0*(total-previousTotal-idle+previousIdle)/(total-previousTotal))));
            previousTotal=total; previousIdle=idle;
        } catch(Exception e) { result.put("cpu_error",e.getMessage()); }
        Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(battery!=null) result.put("battery_percent",100.0*battery.getIntExtra(BatteryManager.EXTRA_LEVEL,0)/Math.max(1,battery.getIntExtra(BatteryManager.EXTRA_SCALE,100)))
            .put("battery_celsius",battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10.0)
            .put("charging",battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0);
        StatFs disk=new StatFs(Environment.getDataDirectory().getAbsolutePath()); result.put("disk_total",disk.getTotalBytes()).put("disk_free",disk.getAvailableBytes());
        long now=android.os.SystemClock.elapsedRealtime(),rx=TrafficStats.getTotalRxBytes(),tx=TrafficStats.getTotalTxBytes();
        if(previousTime>0 && now>previousTime && rx>=previousRx && tx>=previousTx && rx>=0 && tx>=0)
            result.put("rx_per_second",1000.0*(rx-previousRx)/(now-previousTime)).put("tx_per_second",1000.0*(tx-previousTx)/(now-previousTime));
        result.put("ram_used_gb",(memory.totalMem-memory.availMem)/1073741824.0).put("ram_total_gb",memory.totalMem/1073741824.0)
            .put("disk_free_gb",result.optLong("disk_free")/1073741824.0).put("rx_kb_per_second",result.optDouble("rx_per_second",0)/1024)
            .put("tx_kb_per_second",result.optDouble("tx_per_second",0)/1024);
        previousTime=now; previousRx=rx; previousTx=tx; return result;
    }
    JSONArray sensors() throws Exception {
        JSONArray out=new JSONArray(); for(Sensor sensor:context.getSystemService(SensorManager.class).getSensorList(Sensor.TYPE_ALL))
            out.put(new JSONObject().put("name",sensor.getName()).put("type",sensor.getType()).put("vendor",sensor.getVendor()).put("resolution",sensor.getResolution()).put("power_ma",sensor.getPower()));
        return out;
    }
}
