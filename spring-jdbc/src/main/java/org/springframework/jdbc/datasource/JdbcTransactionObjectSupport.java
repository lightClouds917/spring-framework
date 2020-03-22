/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.jdbc.datasource;

import java.sql.SQLException;
import java.sql.Savepoint;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.lang.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.SavepointManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionUsageException;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.util.Assert;

/**
 * JDBC感知的事务对象的便捷基类。
 * 可以包含一个持有JDBC连接的连接句柄，并基于该ConnectionHolder实现SavepointManager。
 * Convenient base class for JDBC-aware transaction objects. Can contain a
 * {@link ConnectionHolder} with a JDBC {@code Connection}, and implements the
 * {@link SavepointManager} interface based on that {@code ConnectionHolder}.
 *
 * <p>Allows for programmatic management of JDBC {@link java.sql.Savepoint Savepoints}.
 * Spring's {@link org.springframework.transaction.support.DefaultTransactionStatus}
 * automatically delegates to this, as it autodetects transaction objects which
 * implement the {@link SavepointManager} interface.
 *
 * @author Juergen Hoeller
 * @since 1.1
 * @see DataSourceTransactionManager
 */
public abstract class JdbcTransactionObjectSupport implements SavepointManager, SmartTransactionObject {

	private static final Log logger = LogFactory.getLog(JdbcTransactionObjectSupport.class);


	/**连接句柄*/
	@Nullable
	private ConnectionHolder connectionHolder;

	/**以前的事务隔离级别*/
	@Nullable
	private Integer previousIsolationLevel;

	/**是否允许保存点*/
	private boolean savepointAllowed = false;


	public void setConnectionHolder(@Nullable ConnectionHolder connectionHolder) {
		this.connectionHolder = connectionHolder;
	}

	public ConnectionHolder getConnectionHolder() {
		Assert.state(this.connectionHolder != null, "No ConnectionHolder available");
		return this.connectionHolder;
	}

	public boolean hasConnectionHolder() {
		return (this.connectionHolder != null);
	}

	public void setPreviousIsolationLevel(@Nullable Integer previousIsolationLevel) {
		this.previousIsolationLevel = previousIsolationLevel;
	}

	@Nullable
	public Integer getPreviousIsolationLevel() {
		return this.previousIsolationLevel;
	}

	public void setSavepointAllowed(boolean savepointAllowed) {
		this.savepointAllowed = savepointAllowed;
	}

	public boolean isSavepointAllowed() {
		return this.savepointAllowed;
	}

	@Override
	public void flush() {
		// no-op
	}


	//---------------------------------------------------------------------
	// SavepointManager的实现
	// Implementation of SavepointManager
	//---------------------------------------------------------------------

	/**
	 * 此实现创建一个JDBC 3.0保存点并返回它。
	 * This implementation creates a JDBC 3.0 Savepoint and returns it.
	 * @see java.sql.Connection#setSavepoint
	 */
	@Override
	public Object createSavepoint() throws TransactionException {
		//获取ConnectionHolder
		ConnectionHolder conHolder = getConnectionHolderForSavepoint();
		try {
			//如果ConnectionHolder不支持使用保存点
			if (!conHolder.supportsSavepoints()) {
				throw new NestedTransactionNotSupportedException(
						"Cannot create a nested transaction because savepoints are not supported by your JDBC driver");
			}
			//如果ConnectionHolder被设置为仅回滚形式,抛出异常
			if (conHolder.isRollbackOnly()) {
				throw new CannotCreateTransactionException(
						"Cannot create savepoint for transaction which is already marked as rollback-only");
			}
			//创建保存点
			return conHolder.createSavepoint();
		}
		catch (SQLException ex) {
			throw new CannotCreateTransactionException("Could not create JDBC savepoint", ex);
		}
	}

	/**
	 * This implementation rolls back to the given JDBC 3.0 Savepoint.
	 * @see java.sql.Connection#rollback(java.sql.Savepoint)
	 */
	@Override
	public void rollbackToSavepoint(Object savepoint) throws TransactionException {
		ConnectionHolder conHolder = getConnectionHolderForSavepoint();
		try {
			//撤销该保存点之后所做的所有更改
			conHolder.getConnection().rollback((Savepoint) savepoint);
			//重置仅回滚状态为false
			conHolder.resetRollbackOnly();
		}
		catch (Throwable ex) {
			throw new TransactionSystemException("Could not roll back to JDBC savepoint", ex);
		}
	}

	/**
	 * This implementation releases the given JDBC 3.0 Savepoint.
	 * @see java.sql.Connection#releaseSavepoint
	 */
	@Override
	public void releaseSavepoint(Object savepoint) throws TransactionException {
		////获取链接句柄
		ConnectionHolder conHolder = getConnectionHolderForSavepoint();
		try {
			//从链接句柄获取链接，释放保存点
			conHolder.getConnection().releaseSavepoint((Savepoint) savepoint);
		}
		catch (Throwable ex) {
			logger.debug("Could not explicitly release JDBC savepoint", ex);
		}
	}

	/**
	 * 了保存点使用返回ConnectionHolder
	 * @return
	 * @throws TransactionException
	 */
	protected ConnectionHolder getConnectionHolderForSavepoint() throws TransactionException {
		//如果不允许使用保存点，抛出异常：事务管理器不允许嵌套事务
		if (!isSavepointAllowed()) {
			throw new NestedTransactionNotSupportedException(
					"Transaction manager does not allow nested transactions");
		}
		//如果ConnectionHolder为null,抛出异常：不公开JDBC事务时无法创建嵌套事务
		if (!hasConnectionHolder()) {
			throw new TransactionUsageException(
					"Cannot create nested transaction when not exposing a JDBC transaction");
		}
		return getConnectionHolder();
	}

}
