package com.localvibe.utils;

public interface ILock {
    public boolean getLock(long timeOutSecond);

    public void unlock();
}
