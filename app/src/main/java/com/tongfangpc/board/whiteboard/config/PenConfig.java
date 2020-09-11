package com.tongfangpc.board.whiteboard.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;

import java.lang.ref.WeakReference;


public class PenConfig {
    private float stokeWidth;
    private int color;
    WeakReference<Context> contextWeakReference;

    public PenConfig(Context context) {
        contextWeakReference = new WeakReference<>(context);
        loadDefaultConfig();
    }

    public PenConfig(PenConfig penConfig) {
        this(penConfig.contextWeakReference.get());
        this.setColor(penConfig.getColor());
        this.setStokeWidth(penConfig.getStokeWidth());
    }


    private void loadDefaultConfig() {
        stokeWidth = 10;
        color = Color.BLACK;
    }
    private void loadConfig() {
        loadDefaultConfig();
        SharedPreferences sp = getReferences();
        if(sp != null) {
            stokeWidth = sp.getFloat("STROKE_WIDTH", 10);
            color = sp.getInt("COLOR", Color.BLACK);
        }
    }

    public float getStokeWidth() {
        return stokeWidth;
    }


    public int getColor() {
        return color;
    }

    public PenConfig setColor(int color) {
        this.color = color;
        getEditor().putInt("COLOR", color).apply();
        return this;
    }


    public PenConfig setStokeWidth(float stokeWidth) {
        this.stokeWidth = stokeWidth;
        getEditor().putFloat("STROKE_WIDTH", stokeWidth).apply();
        return this;
    }

    public void modify(Paint paint) {
        paint.setColor(color);
        paint.setStrokeWidth(stokeWidth);
        paint.setStyle(Paint.Style.STROKE);
    }

    public SharedPreferences getReferences() {
        Context context = contextWeakReference.get();
        if(context != null) {
            SharedPreferences sp = context.getSharedPreferences("DEFAULT_PEN_CONFIG", Context.MODE_PRIVATE);
            return sp;
        }
        return null;
    }

    public SharedPreferences.Editor getEditor() {
        SharedPreferences sp = getReferences();
        if(sp != null) {
            return sp.edit();
        }
        return null;
    }
}
