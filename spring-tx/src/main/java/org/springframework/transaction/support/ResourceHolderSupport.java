/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.transaction.support;

import java.util.Date;

import org.springframework.lang.Nullable;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;

/**
 *  * 资源持有者的便捷基类
 *  * 对参与事务具有仅回滚支持。
 *  * 可以在确定的秒数或者毫秒数后过期，以确定事务超时。
 *
 * Convenient base class for resource holders.
 *
 * <p>Features rollback-only support for participating transactions.
 * Can expire after a certain number of seconds or milliseconds
 * in order to determine a transactional timeout.
 *
 * @author Juergen Hoeller
 * @since 02.02.2004
 * @see org.springframework.jdbc.datasource.DataSourceTransactionManager#doBegin
 * @see org.springframework.jdbc.datasource.DataSourceUtils#applyTransactionTimeout
 */
public abstract class ResourceHolderSupport implements ResourceHolder {

	/**资源是否与事务同步*/
	private boolean synchronizedWithTransaction = false;

	/**是否仅回滚*/
	private boolean rollbackOnly = false;

	@Nullable
	private Date deadline;

	/**
	 * 引用计数器，初始值为0
	 *
	 * 一个holder被请求和释放次数的计数器，如果请求次数大于释放次数，则referenceCount>0,
	 * 此条件可用用来判断该holder是否被引用，如果没有，则释放掉holder连接。
	 * */
	private int referenceCount = 0;

	/**资源持有者是否无效*/
	private boolean isVoid = false;


	/**
	 * 将资源标记为与事务同步
	 * Mark the resource as synchronized with a transaction.
	 * @see org.springframework.jdbc.datasource.DataSourceTransactionManager#doBegin(Object, TransactionDefinition)
	 */
	public void setSynchronizedWithTransaction(boolean synchronizedWithTransaction) {
		this.synchronizedWithTransaction = synchronizedWithTransaction;
	}

	/**
	 * 返回资源是否与事务同步
	 * Return whether the resource is synchronized with a transaction.
	 */
	public boolean isSynchronizedWithTransaction() {
		return this.synchronizedWithTransaction;
	}

	/**
	 * 将资源事务标记为仅回滚。
	 * Mark the resource transaction as rollback-only.
	 */
	public void setRollbackOnly() {
		this.rollbackOnly = true;
	}

	/**
	 * 重置此资源事务的仅回滚状态
	 * 只有在真正使用自定义资源回滚的自定义回滚步骤之后才真正调用它，例如，如果有保存点。
	 * Reset the rollback-only status for this resource transaction.
	 * <p>Only really intended to be called after custom rollback steps which
	 * keep the original resource in action, e.g. in case of a savepoint.
	 * @since 5.0
	 * @see org.springframework.transaction.SavepointManager#rollbackToSavepoint
	 */
	public void resetRollbackOnly() {
		this.rollbackOnly = false;
	}

	/**
	 * 返回资源事务是否被标记为仅回滚状态
	 * Return whether the resource transaction is marked as rollback-only.
	 */
	public boolean isRollbackOnly() {
		return this.rollbackOnly;
	}

	/**
	 * 设置此对象的超时时间，以s计
	 * Set the timeout for this object in seconds.
	 * @param seconds number of seconds until expiration
	 */
	public void setTimeoutInSeconds(int seconds) {
		setTimeoutInMillis(seconds * 1000L);
	}

	/**
	 * 设置此对象的超时时间，以ms计
	 * Set the timeout for this object in milliseconds.
	 * @param millis number of milliseconds until expiration
	 */
	public void setTimeoutInMillis(long millis) {
		//赋值deadline = 当前时间+超时时间
		this.deadline = new Date(System.currentTimeMillis() + millis);
	}

	/**
	 * Return whether this object has an associated timeout.
	 */
	public boolean hasTimeout() {
		return (this.deadline != null);
	}

	/**
	 * Return the expiration deadline of this object.
	 * @return the deadline as Date object
	 */
	@Nullable
	public Date getDeadline() {
		return this.deadline;
	}

	/**
	 * Return the time to live for this object in seconds.
	 * Rounds up eagerly, e.g. 9.00001 still to 10.
	 * @return number of seconds until expiration
	 * @throws TransactionTimedOutException if the deadline has already been reached
	 */
	public int getTimeToLiveInSeconds() {
		double diff = ((double) getTimeToLiveInMillis()) / 1000;
		int secs = (int) Math.ceil(diff);
		checkTransactionTimeout(secs <= 0);
		return secs;
	}

	/**
	 * 返回此对象的剩余生存时间，单位ms
	 * Return the time to live for this object in milliseconds.
	 * @return number of milliseconds until expiration
	 * @throws TransactionTimedOutException if the deadline has already been reached
	 */
	public long getTimeToLiveInMillis() throws TransactionTimedOutException{
		if (this.deadline == null) {
			throw new IllegalStateException("No timeout specified for this resource holder");
		}
		//剩余生存时间 = 用deadline-当前时间               （deadline设置时是=当前时间+设定的超时时间）
		long timeToLive = this.deadline.getTime() - System.currentTimeMillis();
		//检验是否到达deadline，到了会设置事务为仅回滚并抛出异常
		checkTransactionTimeout(timeToLive <= 0);
		return timeToLive;
	}

	/**
	 * 如果到达deadline，设置事务为仅回滚，并抛出超时异常
	 * Set the transaction rollback-only if the deadline has been reached,
	 * and throw a TransactionTimedOutException.
	 */
	private void checkTransactionTimeout(boolean deadlineReached) throws TransactionTimedOutException {
		if (deadlineReached) {
			setRollbackOnly();
			throw new TransactionTimedOutException("Transaction timed out: deadline was " + this.deadline);
		}
	}

	/**
	 * 如果资源持有者被请求过，将引用计数加1（即有人请求拥有获取资源）
	 * Increase the reference count by one because the holder has been requested
	 * (i.e. someone requested the resource held by it).
	 */
	public void requested() {
		this.referenceCount++;
	}

	/**
	 * 如果资源持有者已被释放，将引用计数器减1（即有人释放了它拥有的资源）
	 * Decrease the reference count by one because the holder has been released
	 * (i.e. someone released the resource held by it).
	 */
	public void released() {
		this.referenceCount--;
	}

	/**
	 * 返回是否仍然有对此资源持有者的公开引用
	 * Return whether there are still open references to this holder.
	 */
	public boolean isOpen() {
		return (this.referenceCount > 0);
	}

	/**
	 * 清除此资源持有者的事务状态
	 * Clear the transactional state of this resource holder.
	 */
	public void clear() {
		this.synchronizedWithTransaction = false;
		this.rollbackOnly = false;
		this.deadline = null;
	}

	/**
	 * 重置此资源持有者事务状态，以及引用计数器
	 * Reset this resource holder - transactional state as well as reference count.
	 */
	@Override
	public void reset() {
		clear();
		this.referenceCount = 0;
	}

	/**
	 * 解除资源持有者与事务同步的绑定
	 * */
	@Override
	public void unbound() {
		this.isVoid = true;
	}

	@Override
	public boolean isVoid() {
		return this.isVoid;
	}

}
