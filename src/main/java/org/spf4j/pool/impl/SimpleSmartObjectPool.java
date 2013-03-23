/*
 * Copyright (c) 2001, Zoltan Farkas All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.spf4j.pool.impl;

import com.google.common.collect.LinkedHashMultimap;
import org.spf4j.base.Exceptions;
import org.spf4j.base.Pair;
import org.spf4j.pool.ObjectBorower;
import org.spf4j.pool.ObjectCreationException;
import org.spf4j.pool.ObjectDisposeException;
import org.spf4j.pool.ObjectPool;
import org.spf4j.pool.SmartObjectPool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 * @author zoly
 */
public class SimpleSmartObjectPool<T> implements SmartObjectPool<T> {

    private int maxSize;
    private final LinkedHashMultimap<ObjectBorower<T>, T> borrowedObjects = LinkedHashMultimap.create();
    private final List<T> availableObjects = new ArrayList<T>();
    private final ReentrantLock lock;
    private final Condition available;
    private final ObjectPool.Factory<T> factory;
    private final long timeoutMillis;

    public SimpleSmartObjectPool(int initialSize, int maxSize, ObjectPool.Factory<T> factory, long timeoutMillis, boolean fair) throws ObjectCreationException {
        this.maxSize = maxSize;
        this.factory = factory;
        this.timeoutMillis = timeoutMillis;
        this.lock = new ReentrantLock(true);
        this.available = this.lock.newCondition();
        for (int i = 0; i < initialSize; i++) {
            availableObjects.add(factory.create());
        }
    }

    @Override
    public T borrowObject(ObjectBorower borower) throws InterruptedException,
            TimeoutException, ObjectCreationException {
        lock.lock();
        try {
            if (availableObjects.size() > 0) {
                Iterator<T> it = availableObjects.iterator();
                T object = it.next();
                it.remove();
                borrowedObjects.put(borower, object);
                return object;
            } else if (borrowedObjects.size() < maxSize) {
                T object = factory.create();
                borrowedObjects.put(borower, object);
                return object;
            } else {
                if (borrowedObjects.isEmpty()) {
                    throw new RuntimeException("Pool size is probably closing down or is missconfigured withe size 0");
                }
                for (ObjectBorower<T> b : borrowedObjects.keySet()) {
                    if (borower != b) {
                        T object = b.returnObjectIfNotInUse();
                        if (object != null) {
                            if (!borrowedObjects.remove(b, object)) {
                                throw new IllegalStateException("Returned Object hasn't been borrowed " + object);
                            }
                            borrowedObjects.put(borower, object);
                            return object;
                        }
                    }
                }
                Object object;
                do {
                    Iterator<ObjectBorower<T>> itt = borrowedObjects.keySet().iterator();
                    ObjectBorower<T> b = itt.next();
                    while (b == borower && itt.hasNext()) {
                        b = itt.next();
                    }
                    if (b == borower) {
                        throw new IllegalStateException("Borrower " + b + " already has "
                                + "max number of pool objects");
                    }
                    do {
                        object = b.requestReturnObject();
                        if (object != null && object != ObjectBorower.REQUEST_MADE) {
                            if (!borrowedObjects.remove(b, object)) {
                                throw new IllegalStateException("Returned Object hasn't been borrowed " + object);
                            }
                            borrowedObjects.put(borower, (T) object);
                            return (T) object;
                        }
                    } while (object != ObjectBorower.REQUEST_MADE && (itt.hasNext() && ((b = itt.next()) != null)));
                } while (object != ObjectBorower.REQUEST_MADE);

                while (availableObjects.isEmpty()) {
                    if (!available.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        throw new TimeoutException("Object wait timeout expired " + timeoutMillis);
                    }
                }
                Iterator<T> it = availableObjects.iterator();
                object = it.next();
                it.remove();
                borrowedObjects.put(borower, (T) object);
                return (T) object;

            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void returnObject(T object, ObjectBorower borower) {
        lock.lock();
        try {
            borrowedObjects.remove(borower, object);
            availableObjects.add(object);
            available.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void dispose() throws ObjectDisposeException {
        lock.lock();
        try {
            maxSize = 0;
            List<Pair<ObjectBorower<T>,Object>> returnedObjects = new ArrayList<Pair<ObjectBorower<T>,Object>>();
            for (ObjectBorower<T> b : borrowedObjects.keySet()) {
                Object object = b.requestReturnObject();
                returnedObjects.add(Pair.of(b,object));
            }
            for (Pair<ObjectBorower<T>,Object> objectAndBorrower : returnedObjects) {
                Object object = objectAndBorrower.getSecond();
                if (object != null && object != ObjectBorower.REQUEST_MADE) {
                    if (!borrowedObjects.remove(objectAndBorrower.getFirst(), object)) {
                        throw new IllegalStateException("Returned Object hasn't been borrowed " + object);
                    }
                    availableObjects.add((T) object);
                }
            }         
            ObjectDisposeException exception = disposeReturnedObjects(null);
            while (!borrowedObjects.isEmpty()) {
                if (!available.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    throw new TimeoutException("Object wait timeout expired " + timeoutMillis);
                }
                disposeReturnedObjects(exception);
            }
            if (exception != null) {
                throw exception;
            }
        } catch (Exception e) {
            throw new ObjectDisposeException(e);
        } finally {
            lock.unlock();
        }
    }

    private ObjectDisposeException disposeReturnedObjects(ObjectDisposeException exception) {
        ObjectDisposeException result = exception;
        for (T obj : availableObjects) {
            try {
                factory.dispose(obj);
            } catch (ObjectDisposeException ex) {
                result = Exceptions.chain(ex, result);
            } catch (Exception ex) {
                result = Exceptions.chain(new ObjectDisposeException(ex), result);
            }
        }
        availableObjects.clear();
        return result;
    }

    @Override
    public boolean scan(ScanHandler<T> handler) throws Exception {
        lock.lock();
        try {
            for (ObjectBorower<T> objectBorower : borrowedObjects.keySet()) {
                try {
                    if (!objectBorower.scan(handler)) {
                        return false;
                    }
                } finally {
                    Collection<T> returned = objectBorower.returnObjectsIfNotNeeded();
                    if (returned != null) {
                        for (T ro : returned) {
                            if (!borrowedObjects.remove(objectBorower, ro)) {
                                throw new IllegalStateException("Object returned hasn't been borrowed" + ro);
                            }
                            availableObjects.add(ro);
                        }
                    }
                }
            }
            for (T object : availableObjects) {
                if (!handler.handle(object)) {
                    return false;
                }
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return "SimpleSmartObjectPool{" + "maxSize=" + maxSize + ", borrowedObjects=" + borrowedObjects.values() + ", returnedObjects=" + availableObjects + ", factory=" + factory + ", timeoutMillis=" + timeoutMillis + '}';
        } finally {
            lock.unlock();
        }
    }
}