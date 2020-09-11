package com.tongfangpc.board.whiteboard;

import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.List;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import com.tongfangpc.board.whiteboard.actions.Action;
import com.tongfangpc.board.whiteboard.actions.PathAction;
import com.tongfangpc.board.whiteboard.config.PenConfig;

/*
白班画图视图View，实现基本的笔画、橡皮擦、上一步、下一步等操作；
WhiteboardView的更新没有放在主线程，而通过HandlerThread进行更新，防止绘画量大而阻塞主线程。
WhiteboardView的绘图更新，采用了缓冲区策略，在内存中开辟大小一致的Bitmap，同步渲染历史记录，
在每个更新渲染函数render中，只做核心两件事：
1、将包含历史绘图记录的缓冲区bitmap贴到显示区
2、将最新的一次更新绘制到显示区
这样大大提高了运算的效率，不用每次更新都从头画到尾；
 */
public class WhiteboardView extends SurfaceView implements SurfaceHolder.Callback, Renderable {
    static String TAG = WhiteboardView.class.getSimpleName();

    /*
    该类从外部接收渲染的通知信号，并将渲染的通知发送给HandlerThread，
    使得HandlerThread在子线程对surfaceview进行更新
     */
    public static class RenderThreadHandler extends Handler {

        WeakReference<Renderable> renderableWeakReference;

        public RenderThreadHandler(Looper looper, Renderable renderable) {
            super(looper);
            renderableWeakReference = new WeakReference<>(renderable);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            Renderable renderable = renderableWeakReference.get();
            if (renderable != null) {
                renderable.render();
            }
        }
    }


    /*
    此线程接收renderThreadHandler的通知，并在线程内依次调用render进行渲染
     */
    HandlerThread renderThread;

    /*
    负责接收通知信号，并向HandlerThread传递通知，进行画面渲染
     */
    RenderThreadHandler renderThreadHandler;


    SurfaceHolder surfaceHolder;

    /*
    缓冲区的bitmap
     */
    Bitmap bitmapBuffer;

    /*
    缓冲区的canvas画布
     */
    Canvas bufferCanvas;

    /*
    所有的行为轨迹集合列表
     */
    List<Action> strokerPaths;

    /*
    永远指向下一个当前可写笔画(Path)在行为轨迹集合中的下一个合法可写入的位置索引
    该字段为undo和redo操作提供计算依据
     */
    int nextDoIndex = 0;

    /*
    当前最新的一个滑动轨迹记录
     */
    Path strokePath;

    /*
    当前的画笔
     */
    Paint currentPaint;

    /*
    当前的滑动手势的坐标
     */
    PointF motionPoint = new PointF();

    /*
    存储当前画笔的配置
     */
    PenConfig penConfig;

    /*
    当前的背景颜色
     */
    int backgroundColorId;

    public WhiteboardView(Context context) {
        super(context);
        initWhiteboardView(context);
    }

    public WhiteboardView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        initWhiteboardView(context);
    }

    /*
    初始化基本变量和组件
     */
    public void initWhiteboardView(Context context) {
        currentPaint = new Paint();
        penConfig = new PenConfig(context);
        penConfig.setColor(Color.RED).setStokeWidth(12);
        penConfig.modify(currentPaint);

        backgroundColorId = Color.WHITE;

        strokerPaths = new LinkedList<>();
        strokePath = new Path();

        surfaceHolder = this.getHolder();
        surfaceHolder.addCallback(this);

        setFocusable(true);
        setFocusableInTouchMode(true);
        setKeepScreenOn(true);
        renderThread = new HandlerThread("RenderThread");
    }


    @Override
    public synchronized boolean onTouchEvent(MotionEvent event) {
        int touchCount = event.getPointerCount();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                strokePath.reset();
                motionPoint.set(event.getX(), event.getY());
                strokePath.moveTo(motionPoint.x, motionPoint.y);
                strokePath.lineTo(motionPoint.x, motionPoint.y);
                doRender();
                break;

            case MotionEvent.ACTION_MOVE:
                //Log.d(TAG, "onTouchEvent.MOVE");
                float disX = Math.abs(event.getX() - motionPoint.x),
                        disY = Math.abs((event.getY() - motionPoint.y));
                if (disX >= penConfig.getStokeWidth() || disY >= penConfig.getStokeWidth()) {
                    strokePath.quadTo(motionPoint.x, motionPoint.y, (event.getX() + motionPoint.x) / 2, (event.getY() + motionPoint.y) / 2);
                    motionPoint.set(event.getX(), event.getY());
                    doRender();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                Log.d(TAG, "onTouchEvent.ACTION_CANCEL");
            case MotionEvent.ACTION_UP:
                Log.d(TAG, "onTouchEvent.ACTION_UP");
                if (canRedo()) {
                    while (nextDoIndex > 0 && strokerPaths.size() > nextDoIndex) {
                        strokerPaths.remove(strokerPaths.size() - 1);
                    }
                }
                //当前轨迹加入历史记录
                strokerPaths.add(new PathAction(strokePath, penConfig));
                //当前轨迹画入缓冲区
                bufferCanvas.drawPath(strokePath, currentPaint);
                //历史记录doindex向后移动
                ++nextDoIndex;
                break;
        }
        return true;
    }


    /*
    render方法是最基本的单元渲染函数，由HandlerTread在子线程进行异步调用,包括如下环节：
    1、获取canvas
    2、将包含了之前所有的历史步骤的缓冲区bitmap，先渲染出来，之所以要先渲染bitmap，是因为
    bitmap包含了本次轨迹渲染之前所有的历史记录，如果通过list去遍历效率较低，直接将包含历史
    记录的缓冲区拷贝到显示区，可以节省计算资源。
    3、渲染最新的一笔轨迹
    4、释放canvas
     */
    @Override
    public void render() {
        Canvas canvas = surfaceHolder.lockCanvas();
        if (canvas != null) {
            Log.d(TAG, "render()");
            canvas.drawBitmap(bitmapBuffer, 0, 0, currentPaint);
            canvas.drawPath(strokePath, currentPaint);
            surfaceHolder.unlockCanvasAndPost(canvas);
        }
    }

    /*
    render的触发函数包装
     */
    public void doRender() {
        renderThreadHandler.sendEmptyMessage(0);
    }


    /*
    SurfaceHolder.Callback的回调实现
     */
    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        bitmapBuffer = Bitmap.createBitmap(this.getWidth(), this.getHeight(), Bitmap.Config.ARGB_8888);
        bufferCanvas = new Canvas(bitmapBuffer);
        bufferCanvas.drawColor(backgroundColorId);
        renderThread.start();
        renderThreadHandler = new RenderThreadHandler(renderThread.getLooper(), this);
        doRender();
    }


    /*
    SurfaceHolder.Callback的回调实现
     */
    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
    }


    /*
    SurfaceHolder.Callback的回调实现
     */
    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        //清空消息队列
        renderThreadHandler.removeCallbacksAndMessages(null);
        //线程退出
        renderThread.quit();
        bitmapBuffer.recycle();
    }


    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        if (bitmapBuffer != null && bitmapBuffer.isRecycled() == false) {
            bitmapBuffer.recycle();
            bitmapBuffer = null;
        }
    }


    /*
    nextDoIndex         ^
    strokerPaths    x x
    actionIndex     0 1 2 3 4 5 6
    */
    /*
    根据当前的行为轨迹集合来返回是否可以进行取消一步操作
     */
    public synchronized boolean canUndo() {
        if (nextDoIndex <= 0 || strokerPaths.size() <= 0) {
            return false;
        }
        return true;
    }

    /*
    根据当前的行为轨迹集合来判断返回是否可以进行重做一步操作
     */
    public synchronized boolean canRedo() {
        return nextDoIndex < strokerPaths.size();
    }

    /*
    该方法在当前行为轨迹集合中，进行取消一步操作；
    在实现中，方法只是把下一次行为轨迹写入位置索引(nextDoIndex)向前移动一步，
    并不会立即将后面被取消的操作记录删除，因为用户可能继续redo进行恢复;
    删除的时机放在用户写画的onTouchUp事件中进行判定和处理，
    如果canRedo，说明用户之前使用undo回退到了轨迹集合中间某个点，
    那个点之后有废弃不用的行为轨迹在集合中，那么就把nextDoIndex指向以及其后的行为轨迹都删除
    返回：取消操作是否成功
     */
    public synchronized boolean undo() {
        if (canUndo() == false) {
            return false;
        }
        //只是把索引向前移动一步，保证记录还在，用户可以继续redo
        --nextDoIndex;
        //清屏
        bufferCanvas.drawColor(backgroundColorId);
        //根据0索引到新索引点nextDoIndex之间的轨迹记录，进行重绘
        for (int i = 0; i < nextDoIndex; i++) {
            strokerPaths.get(i).draw(bufferCanvas);
        }
        strokePath.reset();
        doRender();
        return true;
    }


    /*
    在当前行为轨迹集合中，进行重做一步操作
    返回：重做操作是否成功
     */
    public synchronized boolean redo() {
        if (canRedo() == false) {
            return false;
        }
        //只是把索引向后移动一步
        ++nextDoIndex;
        bufferCanvas.drawColor(backgroundColorId);
        //根据0索引到新索引点nextDoIndex之间的轨迹记录，进行重绘
        for (int i = 0; i < nextDoIndex; i++) {
            strokerPaths.get(i).draw(bufferCanvas);
        }
        strokePath.reset();
        doRender();
        return true;
    }

    public void setPenStrokerWidth(float width) {
        penConfig.setStokeWidth(width);
        penConfig.modify(currentPaint);
    }

    public float getPenStrokeWidth() {
        return penConfig.getStokeWidth();
    }

    public void setPenColor(int color) {
        penConfig.setColor(color);
        penConfig.modify(currentPaint);
    }

    public int getPenColor() {
        return penConfig.getColor();
    }
}
