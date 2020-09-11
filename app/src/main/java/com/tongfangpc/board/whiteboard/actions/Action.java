package com.tongfangpc.board.whiteboard.actions;

import android.graphics.Canvas;
import android.graphics.Paint;

public abstract class Action {
    static Paint actionPen;
    static {
        actionPen = new Paint();
    }
    public abstract void draw(Canvas canvas);
}
