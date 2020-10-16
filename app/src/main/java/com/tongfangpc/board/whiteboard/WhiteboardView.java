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
import android.graphics.PathMeasure;
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
WhiteboardView的更新没有放在主线程，而通过HandlerThread进行更新，通过绘画线程更新，防止绘画计算量大而卡顿主线程。
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
    List<Action> historicActions;

    /*
    永远指向下一个当前可写笔画(Path)在行为轨迹集合中的下一个合法可写入的位置索引
    该字段为undo和redo操作提供计算依据
     */
    int nextDoIndex = 0;

    /*
    当前最新的一个滑动轨迹记录
     */
    Path latestStrokePath;

    /*
    当前的画笔
     */
    Paint currentPaint;


    Paint pointPaint;

    /*
    当前的滑动手势的坐标
     */
    PointF motionPoint = new PointF();

    /*
    存储当前画笔的配置
     */
    PenConfig penConfig;


    boolean isSurfaceAvailable;

    int moveCount = 0;

    long motionTime = 0;

    int strokeFragmentWidth = 20;
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
        pointPaint = new Paint();
        pointPaint.setStyle(Paint.Style.STROKE);
        pointPaint.setStrokeCap(Paint.Cap.ROUND);
        pointPaint.setStrokeWidth(18);
        pointPaint.setColor(Color.BLUE);
        penConfig = new PenConfig(context);
        penConfig.setColor(Color.RED).setStokeWidth(12);
        penConfig.modify(currentPaint);

        backgroundColorId = Color.WHITE;

        historicActions = new LinkedList<>();
        latestStrokePath = new Path();

        surfaceHolder = this.getHolder();
        surfaceHolder.addCallback(this);

        setFocusable(true);
        setFocusableInTouchMode(true);
        setKeepScreenOn(true);
    }


    @Override
    public synchronized boolean onTouchEvent(MotionEvent event) {
        int touchCount = event.getPointerCount();
        float distanceToLatestPoint = 0;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                /*清空最新的一笔路径容器，并记录笔画的开始点*/
                latestStrokePath.reset();
                motionPoint.set(event.getX(), event.getY());
                latestStrokePath.moveTo(motionPoint.x, motionPoint.y);
                latestStrokePath.quadTo(motionPoint.x, motionPoint.y, motionPoint.x, motionPoint.y);
                doRender();
                moveCount = 0;
                motionTime = System.currentTimeMillis();
                break;

            case MotionEvent.ACTION_MOVE:
                distanceToLatestPoint  = (float)Math.hypot(event.getY() - motionPoint.y, event.getX() - motionPoint.x);
                if (distanceToLatestPoint > penConfig.getStokeWidth() * 2) {
                    long currentMotionTime = System.currentTimeMillis();
                    long strokeTimeSpan = Math.abs(currentMotionTime - motionTime);
                    float velocity = distanceToLatestPoint / strokeTimeSpan;
                    Log.d(TAG, "onTouchEvent.MOVE:" + "distance=" + distanceToLatestPoint + ",timespan=" + strokeTimeSpan + ",velocity="  + velocity);
                    motionTime = currentMotionTime;
                    latestStrokePath.quadTo(motionPoint.x, motionPoint.y, (event.getX() + motionPoint.x) / 2, (event.getY() + motionPoint.y) / 2);
                    //latestStrokePath.quadTo(motionPoint.x, motionPoint.y, event.getX(), event.getY());
                    bufferCanvas.drawPoint(event.getX(), event.getY(), pointPaint);
                    motionPoint.set(event.getX(), event.getY());
                    doRender();
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                Log.d(TAG, "onTouchEvent.ACTION_CANCEL");
            case MotionEvent.ACTION_UP:
                distanceToLatestPoint  = (float)Math.hypot(event.getY() - motionPoint.y, event.getX() - motionPoint.x);
                long currentMotionTime = System.currentTimeMillis();
                float velocity = distanceToLatestPoint / (currentMotionTime - motionTime);
                Log.d(TAG, "onTouchEvent.ACTION_UP: distance=" + distanceToLatestPoint + ",timespan=" + (currentMotionTime - motionTime) + ",velocity=" + velocity);
                //如果切入点在历史轨迹的某个中间点上，说明用户之前做过undo操作， 那么要把doIndex之后的无效动作删掉
                if (canRedo()) {
                    while (nextDoIndex > 0 && historicActions.size() > nextDoIndex) {
                        historicActions.remove(historicActions.size() - 1);
                    }
                }
                if(distanceToLatestPoint > 0) {
                    latestStrokePath.quadTo(motionPoint.x, motionPoint.y, (event.getX() + motionPoint.x) / 2, (event.getY() + motionPoint.y) / 2);
                    bufferCanvas.drawPoint(event.getX(), event.getY(), pointPaint);
                    doRender();
                }
                //当前轨迹加入历史记录
                historicActions.add(new PathAction(latestStrokePath, penConfig));
                //当前轨迹画入缓冲区
                bufferCanvas.drawPath(latestStrokePath, currentPaint);
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
            canvas.drawPath(latestStrokePath, currentPaint);
            surfaceHolder.unlockCanvasAndPost(canvas);
        }
    }

    /*
    render的触发函数包装
     */
    public void doRender() {
        if (isSurfaceAvailable && renderThreadHandler != null) {
            renderThreadHandler.sendEmptyMessage(0);
        }
    }


    /*
    SurfaceHolder.Callback的回调实现
    当surface被创建或者可用时被调用
    注意，当外层Activty被resume时，也会被调用
     */
    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        renderThread = new HandlerThread("RenderThread");
        renderThread.start();
        if (bitmapBuffer == null) {
            bitmapBuffer = Bitmap.createBitmap(this.getWidth(), this.getHeight(), Bitmap.Config.ARGB_8888);
            bufferCanvas = new Canvas(bitmapBuffer);
            bufferCanvas.drawColor(backgroundColorId);
        }
        //如果路径不为空，说明当前是因为Activity窗体被resume回来的，那么要把缓冲区中的历史轨迹要走一遍s
        if (historicActions.size() > 0 && nextDoIndex > 0) {
            for (int i = 0, j = historicActions.size(); i < j && i < nextDoIndex; i++) {
                historicActions.get(i).draw(bufferCanvas);
            }
        }
        renderThreadHandler = new RenderThreadHandler(renderThread.getLooper(), this);
        isSurfaceAvailable = true;
        //doRender调用一次，主要是为了将初始化画面更新到白板上；
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
        isSurfaceAvailable = false;
        //清空消息队列
        renderThreadHandler.removeCallbacksAndMessages(null);
        //线程退出
        renderThread.quit();
        bitmapBuffer.recycle();
        renderThread = null;
        renderThreadHandler = null;
        bitmapBuffer = null;
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
        if (nextDoIndex <= 0 || historicActions.size() <= 0) {
            return false;
        }
        return true;
    }

    /*
    根据当前的行为轨迹集合来判断返回是否可以进行重做一步操作
     */
    public synchronized boolean canRedo() {
        return nextDoIndex < historicActions.size();
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
            historicActions.get(i).draw(bufferCanvas);
        }
        //将最新一条临时轨迹清空，否则他会随着doRender被更新到画面上
        latestStrokePath.reset();
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
            historicActions.get(i).draw(bufferCanvas);
        }
        //将最新一条临时轨迹清空，否则他会随着doRender被更新到画面上
        latestStrokePath.reset();
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
