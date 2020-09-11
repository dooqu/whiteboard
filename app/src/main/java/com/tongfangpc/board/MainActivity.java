package com.tongfangpc.board;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.tongfangpc.board.whiteboard.WhiteboardView;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class MainActivity extends AppCompatActivity {
    static String TAG = MainActivity.class.getSimpleName();
    Button buttRedo;
    Button buttUndo;
    Button buttIncreateWidth;
    Button buttReduceWidth;
    WhiteboardView whiteboardView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_whiteboard);
        whiteboardView = findViewById(R.id.whiteboardView);
        buttRedo = findViewById(R.id.buttRedo);
        buttUndo = findViewById(R.id.buttUndo);
        buttIncreateWidth = findViewById(R.id.buttIncreaseWidth);
        buttReduceWidth = findViewById(R.id.buttReduceWidth);
        buttUndo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(whiteboardView.canUndo()) {
                    Log.d(TAG, "undo");
                    whiteboardView.undo();
                }
            }
        });

        buttRedo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(whiteboardView.canRedo()) {
                    Log.d(TAG, "redo");
                    whiteboardView.redo();
                }
            }
        });
        buttIncreateWidth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(whiteboardView.getPenStrokeWidth() < 30) {
                    whiteboardView.setPenStrokerWidth(whiteboardView.getPenStrokeWidth() + 1);
                }
            }
        });

        buttReduceWidth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(whiteboardView.getPenStrokeWidth() > 10) {
                    whiteboardView.setPenStrokerWidth(whiteboardView.getPenStrokeWidth() - 1);
                }
            }
        });
    }
}