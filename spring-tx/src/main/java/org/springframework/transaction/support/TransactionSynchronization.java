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

import java.io.Flushable;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事物回调的同步接口，由AbstractPlatformTransactionManager支持；
 * Interface for transaction synchronization callbacks.
 * Supported by AbstractPlatformTransactionManager.
 *
 * TransactionSynchronization的实现可以实现Ordered接口来影响其执行顺序，未实现Ordered接口的同步会被附加到同步链的尾部；
 * <p>TransactionSynchronization implementations can implement the Ordered interface
 * to influence their execution order. A synchronization that does not implement the
 * Ordered interface is appended to the end of the synchronization chain.
 *
 * Spring本身执行的系统同步使用特定的顺序值，允许与其执行顺序进行细粒度的交互（如有有必要）；
 * <p>System synchronizations performed by Spring itself use specific order values,
 * allowing for fine-grained interaction with their execution order (if necessary).
 *
 * @author Juergen Hoeller
 * @since 02.06.2003
 * @see TransactionSynchronizationManager
 * @see AbstractPlatformTransactionManager
 * @see org.springframework.jdbc.datasource.DataSourceUtils#CONNECTION_SYNCHRONIZATION_ORDER
 */
public interface TransactionSynchronization extends Flushable {

	//正确提交时的完成状态
	/** Completion status in case of proper commit. */
	int STATUS_COMMITTED = 0;

	//回滚正确时的完成状态
	/** Completion status in case of proper rollback. */
	int STATUS_ROLLED_BACK = 1;

	/** Completion status in case of heuristic mixed completion or system errors. */
	int STATUS_UNKNOWN = 2;


	/**
	 * 暂停此同步
	 * 如果管理资源，应该取消与TransactionSynchronizationManager的资源绑定；
	 * Suspend this synchronization.
	 * Supposed to unbind resources from TransactionSynchronizationManager if managing any.
	 * @see TransactionSynchronizationManager#unbindResource
	 */
	default void suspend() {
	}

	/**
	 * 恢复此同步
	 * 如果管理资源，则应将资源重新绑定到TransactionSynchronizationManager;
	 * Resume this synchronization.
	 * Supposed to rebind resources to TransactionSynchronizationManager if managing any.
	 * @see TransactionSynchronizationManager#bindResource
	 */
	default void resume() {
	}

	/**
	 * 将底层会话刷新到数据存储区；
	 * Flush the underlying session to the datastore, if applicable:
	 * for example, a Hibernate/JPA session.
	 * @see org.springframework.transaction.TransactionStatus#flush()
	 */
	@Override
	default void flush() {
	}

	/**
	 * 在事务提交前执行；
	 * 这个回调的发生不意味着事务一定会被提交，这个方法被调用后，仍然有可能发生回滚；
	 * Invoked before transaction commit (before "beforeCompletion").
	 * Can e.g. flush transactional O/R Mapping sessions to the database.
	 * <p>This callback does <i>not</i> mean that the transaction will actually be committed.
	 * A rollback decision can still occur after this method has been called. This callback
	 * is rather meant to perform work that's only relevant if a commit still has a chance
	 * to happen, such as flushing SQL statements to the database.
	 * <p>Note that exceptions will get propagated to the commit caller and cause a
	 * rollback of the transaction.
	 * @param readOnly whether the transaction is defined as read-only transaction
	 * @throws RuntimeException in case of errors; will be <b>propagated to the caller</b>
	 * (note: do not throw TransactionException subclasses here!)
	 * @see #beforeCompletion
	 */
	default void beforeCommit(boolean readOnly) {
	}

	/**
	 * 在事务提交/回滚之前调用。
	 * 可以在事务完成之前执行资源清理。
	 * <p>即使{@code beforeCommit}引发异常，此方法也会在{@code beforeCommit}之后调用。
	 * 对于任何结果，此回调都允许在事务完成之前关闭资源。
	 * Invoked before transaction commit/rollback.
	 * Can perform resource cleanup <i>before</i> transaction completion.
	 * <p>This method will be invoked after {@code beforeCommit}, even when
	 * {@code beforeCommit} threw an exception. This callback allows for
	 * closing resources before transaction completion, for any outcome.
	 * @throws RuntimeException in case of errors; will be <b>logged but not propagated</b>
	 * (note: do not throw TransactionException subclasses here!)
	 * @see #beforeCommit
	 * @see #afterCompletion
	 */
	default void beforeCompletion() {
	}

	/**
	 * 事务提交后调用。
	 * 可以在事主要务成功提交后立即执行进一步的操作。
	 * <p>例如提交应该在成功提交主要事务后执行的其他操作，例如确认消息或电子邮件。
	 *
	 * <b>注意：</ b>事务将已经提交，但是事务资源可能仍处于活动状态并且可以访问。
	 * 结果，此时触发的任何数据访问代码仍将“参与”原始事务，从而允许执行一些清除操作（不再执行任何提交！），
	 * 除非它明确声明需要在单独的事务中运行。
	 *
	 * 因此：<b>对于从此处调用的任何事务操作，请使用{@code PROPAGATION_REQUIRES_NEW}。
	 *
	 * Invoked after transaction commit. Can perform further operations right
	 * <i>after</i> the main transaction has <i>successfully</i> committed.
	 * <p>Can e.g. commit further operations that are supposed to follow on a successful
	 * commit of the main transaction, like confirmation messages or emails.
	 * <p><b>NOTE:</b> The transaction will have been committed already, but the
	 * transactional resources might still be active and accessible. As a consequence,
	 * any data access code triggered at this point will still "participate" in the
	 * original transaction, allowing to perform some cleanup (with no commit following
	 * anymore!), unless it explicitly declares that it needs to run in a separate
	 * transaction. Hence: <b>Use {@code PROPAGATION_REQUIRES_NEW} for any
	 * transactional operation that is called from here.</b>
	 * @throws RuntimeException in case of errors; will be <b>propagated to the caller</b>
	 * (note: do not throw TransactionException subclasses here!)
	 */
	default void afterCommit() {
	}

	/**
	 * 在事务提交/回滚后调用。
	 * 可以在事务完成后执行资源清理。
	 *
	 * 注意：事务将已经提交或回滚，但是事务资源可能仍处于活动状态并且可以访问。
	 * 结果，此时触发的任何数据访问代码仍将“参与”原始事务，从而允许执行一些清除操作（不再执行任何提交！），除非它明确声明需要在单独的事务中运行。
	 *
	 * 因此：<b>对于从此处调用的任何事务操作，请使用{@code PROPAGATION_REQUIRES_NEW}。
	 *
	 * Invoked after transaction commit/rollback.
	 * Can perform resource cleanup <i>after</i> transaction completion.
	 * <p><b>NOTE:</b> The transaction will have been committed or rolled back already,
	 * but the transactional resources might still be active and accessible. As a
	 * consequence, any data access code triggered at this point will still "participate"
	 * in the original transaction, allowing to perform some cleanup (with no commit
	 * following anymore!), unless it explicitly declares that it needs to run in a
	 * separate transaction. Hence: <b>Use {@code PROPAGATION_REQUIRES_NEW}
	 * for any transactional operation that is called from here.</b>
	 * @param status completion status according to the {@code STATUS_*} constants
	 * @throws RuntimeException in case of errors; will be <b>logged but not propagated</b>
	 * (note: do not throw TransactionException subclasses here!)
	 * @see #STATUS_COMMITTED
	 * @see #STATUS_ROLLED_BACK
	 * @see #STATUS_UNKNOWN
	 * @see #beforeCompletion
	 */
	default void afterCompletion(int status) {
	}

}
