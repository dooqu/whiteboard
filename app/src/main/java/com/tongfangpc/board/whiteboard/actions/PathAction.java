package com.tongfangpc.board.whiteboard.actions;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import com.tongfangpc.board.whiteboard.config.PenConfig;

public class PathAction extends Action {
    Path penPath;
    PenConfig penConfig;
    public PathAction(Path path, PenConfig penConfig) {
        this.penPath = new Path(path);
        this.penConfig = new PenConfig(penConfig);
    }

    @Override
    public void draw(Canvas canvas) {
        penConfig.modify(actionPen);
        canvas.drawPath(penPath, actionPen);
    }
}
