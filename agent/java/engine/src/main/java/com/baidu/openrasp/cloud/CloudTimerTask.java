package com.baidu.openrasp.cloud;

/**
 * Created by tyy on 19-5-17.
 *
 * 云控定时任务基类
 */
public abstract class CloudTimerTask implements Runnable {

    private int sleepTime;

    private volatile boolean isAlive = true;

    private volatile boolean isSuspended = false;

    public CloudTimerTask(int sleepTime) {
        this.sleepTime = sleepTime;
    }

    public void start() {
        Thread taskThread = new Thread(this);
        taskThread.setDaemon(true);
        taskThread.start();
    }

    public void stop() {
        this.isAlive = false;
    }

    public void suspend() {
        this.isSuspended = true;
    }

    public void resume() {
        this.isSuspended = false;
    }

    public void run() {
        while (isAlive) {
            try {
                if (!isSuspended) {
                    try {
                        execute();
                    } catch (Throwable t) {
                        handleError(t);
                    }
                    try {
                        // 和上面分开处理，避免心跳失败不走 sleep,不能放到 execute 之前，会导致第一次心跳不能马上运行
                        Thread.sleep(sleepTime * 1000);
                    } catch (Throwable t) {
                        handleError(t);
                    }
                }
            } catch (Throwable t) {
                System.out.println("OpenRASP cloud task failed: " + t.getMessage());
                t.printStackTrace();
            }
        }
    }

    abstract public void execute();

    abstract public void handleError(Throwable t);

    public void setAlive(boolean alive) {
        isAlive = alive;
    }
}
