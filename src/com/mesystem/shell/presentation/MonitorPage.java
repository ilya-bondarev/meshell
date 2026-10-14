package com.mesystem.shell;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class MonitorPage extends ScrollView implements SensorEventListener {
    private final ModuleHub hub;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SensorManager sensors;
    private final TextView cpuText,ramText,batteryText,networkText,sensorText;
    private final SparkView cpu,ram,sensorPlot;
    private final List<Sensor> choices=new ArrayList<>();
    private boolean active,inFlight; private int selected; private long lastSensor;
    private OpenAiClient.Cancel pending;
    private final Runnable poll=this::sample;
    MonitorPage(Context c,ModuleHub hub) {
        super(c); this.hub=hub; sensors=c.getSystemService(SensorManager.class); setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        LinearLayout body=Ui.bounded(c); Ui.pad(body,18,8,18,24); addView(body,Ui.centred(-2));
        Ui.Grid meters=new Ui.Grid(c,260,12,2); body.addView(meters,Ui.size(c,-1,-2)); Ui.gap(body,12);
        LinearLayout cpuCard=card(meters,Language.text(R.string.cpu)); cpuText=Ui.bold(c,Language.text(R.string.sampling),26,Ui.INK); cpuCard.addView(cpuText); cpu=new SparkView(c,true); cpuCard.addView(cpu,Ui.size(c,-1,90));
        LinearLayout ramCard=card(meters,Language.text(R.string.memory)); ramText=Ui.bold(c,"—",26,Ui.INK); ramCard.addView(ramText); ram=new SparkView(c,true); ramCard.addView(ram,Ui.size(c,-1,90));
        LinearLayout device=card(body,Language.text(R.string.battery_and_network)); batteryText=Ui.text(c,"",13,Ui.INK); batteryText.setLineSpacing(Ui.dp(c,3),1); device.addView(batteryText);
        Ui.gap(device,10); Ui.divider(device); Ui.gap(device,10); networkText=Ui.text(c,"",13,Ui.INK); networkText.setLineSpacing(Ui.dp(c,3),1); device.addView(networkText); Ui.gap(body,12);
        LinearLayout live=card(body,Language.text(R.string.live_sensors));
        List<String> names=new ArrayList<>();
        for(int type:new int[]{Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_GYROSCOPE,Sensor.TYPE_MAGNETIC_FIELD,Sensor.TYPE_LIGHT,Sensor.TYPE_PROXIMITY,Sensor.TYPE_PRESSURE}) {
            Sensor sensor=sensors.getDefaultSensor(type); if(sensor!=null) { choices.add(sensor); names.add(sensor.getName()); }
        }
        Spinner picker=new Spinner(c); picker.setAdapter(new ArrayAdapter<>(c,android.R.layout.simple_spinner_dropdown_item,names));
        picker.setBackground(Ui.shape(c,Ui.PANEL2,14,Ui.BORDER)); Ui.pad(picker,8,0,8,0); picker.setPopupBackgroundDrawable(Ui.shape(c,Ui.PANEL2,16,Ui.BORDER));
        live.addView(picker,Ui.size(c,-1,48)); Ui.gap(live,10);
        sensorText=Ui.text(c,choices.isEmpty()?Language.text(R.string.no_sensors_available):"",12,Ui.MUTED); live.addView(sensorText);
        sensorPlot=new SparkView(c,false); live.addView(sensorPlot,Ui.size(c,-1,145));
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int position,long id) { selected=position; bindSensor(); }
            public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
    }
    private LinearLayout card(android.view.ViewGroup parent,String title) {
        LinearLayout card=Ui.card(getContext()); card.addView(Ui.section(getContext(),title)); Ui.gap(card,8);
        parent.addView(card,parent instanceof LinearLayout?Ui.size(getContext(),-1,-2):new android.view.ViewGroup.LayoutParams(-1,-2)); return card;
    }
    void active(boolean value) { active=value; main.removeCallbacks(poll); if(pending!=null && !value) pending.stop(); bindSensor(); if(value) sample(); }
    private void bindSensor() { sensors.unregisterListener(this); if(active && !choices.isEmpty()) sensors.registerListener(this,choices.get(Math.min(selected,choices.size()-1)),SensorManager.SENSOR_DELAY_NORMAL); }
    private void sample() {
        if(!active || inFlight) return; inFlight=true; OpenAiClient.Cancel cancel=new OpenAiClient.Cancel(); pending=cancel;
        new Thread(() -> {
            JSONObject result=null; String error=null;
            try { result=hub.telemetry.sample(cancel); } catch(Exception e) { error=e.getMessage(); }
            JSONObject value=result; String failure=error;
            main.post(() -> {
                inFlight=false; if(!active) return;
                if(value!=null) {
                    if(value.has("cpu_percent")) { float p=(float)value.optDouble("cpu_percent"); cpu.add(p); cpuText.setText(String.format(Language.locale(),Language.text(R.string.format_cores),p,value.optInt("cores"))); }
                    else cpuText.setText(value.has("cpu_error")?Language.text(R.string.cpu_unavailable):Language.text(R.string.sampling));
                    float percent=(float)value.optDouble("ram_used_percent"); ram.add(percent);
                    ramText.setText(String.format(Language.locale(),Language.text(R.string.format_memory),value.optDouble("ram_used_gb"),value.optDouble("ram_total_gb")));
                    batteryText.setText(String.format(Language.locale(),Language.text(R.string.battery_0f_1f_c_s_free_1f_gb),value.optDouble("battery_percent"),value.optDouble("battery_celsius"),value.optBoolean("charging")?Language.text(R.string.charging):"",value.optDouble("disk_free_gb")));
                    networkText.setText(String.format(Language.locale(),Language.text(R.string.android_traffic_1f_kb_s_1f_kb_s),value.optDouble("rx_kb_per_second",0),value.optDouble("tx_kb_per_second",0)));
                } else cpuText.setText(failure);
                main.postDelayed(poll,2000);
            });
        },"meshell-monitor").start();
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if(!active || android.os.SystemClock.elapsedRealtime()-lastSensor<180) return; lastSensor=android.os.SystemClock.elapsedRealtime();
        if(event.values.length>=3) { sensorPlot.add(event.values[0],event.values[1],event.values[2]); sensorText.setText(String.format(Language.locale(),"X %.2f   Y %.2f   Z %.2f",event.values[0],event.values[1],event.values[2])); }
        else { sensorPlot.add(event.values[0]); String unit=event.sensor.getType()==Sensor.TYPE_LIGHT?Language.text(R.string.lx):event.sensor.getType()==Sensor.TYPE_PROXIMITY?Language.text(R.string.cm):"";
            sensorText.setText(String.format(Language.locale(),"%.2f%s",event.values[0],unit)); }
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) { }
}
