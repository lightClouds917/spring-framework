/*
 * Copyright 2002-2019 the original author or authors.
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

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.Constants;
import org.springframework.lang.Nullable;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.InvalidTimeoutException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSuspensionNotSupportedException;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * Abstract base class that implements Spring's standard transaction workflow,
 * serving as basis for concrete platform transaction managers like
 * {@link org.springframework.transaction.jta.JtaTransactionManager}.
 *
 * <p>This base class provides the following workflow handling:
 * <ul>
 * <li>determines if there is an existing transaction;
 * <li>applies the appropriate propagation behavior;
 * <li>suspends and resumes transactions if necessary;
 * <li>checks the rollback-only flag on commit;
 * <li>applies the appropriate modification on rollback
 * (actual rollback or setting rollback-only);
 * <li>triggers registered synchronization callbacks
 * (if transaction synchronization is active).
 * </ul>
 *
 * <p>Subclasses have to implement specific template methods for specific
 * states of a transaction, e.g.: begin, suspend, resume, commit, rollback.
 * The most important of them are abstract and must be provided by a concrete
 * implementation; for the rest, defaults are provided, so overriding is optional.
 *
 * <p>Transaction synchronization is a generic mechanism for registering callbacks
 * that get invoked at transaction completion time. This is mainly used internally
 * by the data access support classes for JDBC, Hibernate, JPA, etc when running
 * within a JTA transaction: They register resources that are opened within the
 * transaction for closing at transaction completion time, allowing e.g. for reuse
 * of the same Hibernate Session within the transaction. The same mechanism can
 * also be leveraged for custom synchronization needs in an application.
 *
 * <p>The state of this class is serializable, to allow for serializing the
 * transaction strategy along with proxies that carry a transaction interceptor.
 * It is up to subclasses if they wish to make their state to be serializable too.
 * They should implement the {@code java.io.Serializable} marker interface in
 * that case, and potentially a private {@code readObject()} method (according
 * to Java serialization rules) if they need to restore any transient state.
 *
 * @author Juergen Hoeller
 * @since 28.03.2003
 * @see #setTransactionSynchronization
 * @see TransactionSynchronizationManager
 * @see org.springframework.transaction.jta.JtaTransactionManager
 */
@SuppressWarnings("serial")
public abstract class AbstractPlatformTransactionManager implements PlatformTransactionManager, Serializable {

	/**
	 * 始终激活事务同步，即使由于PROPAGATION_SUPPORTS这种传播属性导致的空事务，或者没有后台事务
	 * Always activate transaction synchronization, even for "empty" transactions
	 * that result from PROPAGATION_SUPPORTS with no existing backend transaction.
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_SUPPORTS
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_NOT_SUPPORTED
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_NEVER
	 */
	public static final int SYNCHRONIZATION_ALWAYS = 0;

	/**
	 * 仅仅激活实际存在的事务的事务同步
	 * 这意味着，由PROPAGATION_SUPPORTS这种传播属性导致的空事务或者没有后台事务时，是不激活事务同步的
	 * Activate transaction synchronization only for actual transactions,
	 * that is, not for empty ones that result from PROPAGATION_SUPPORTS with
	 * no existing backend transaction.
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_REQUIRED
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_MANDATORY
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_REQUIRES_NEW
	 */
	public static final int SYNCHRONIZATION_ON_ACTUAL_TRANSACTION = 1;

	/**
	 * 从不主动激活事务同步，即使是对于实际事务
	 * Never active transaction synchronization, not even for actual transactions.
	 */
	public static final int SYNCHRONIZATION_NEVER = 2;


	/**
	 * AbstractPlatformTransactionManager的常量实例
	 * Constants instance for AbstractPlatformTransactionManager. */
	private static final Constants constants = new Constants(AbstractPlatformTransactionManager.class);


	protected transient Log logger = LogFactory.getLog(getClass());

	//TODO
	/**此事务管理器是否应激活线程绑定的事务同步支持。*/
	private int transactionSynchronization = SYNCHRONIZATION_ALWAYS;

	private int defaultTimeout = TransactionDefinition.TIMEOUT_DEFAULT;

	private boolean nestedTransactionAllowed = false;

	/**
	 * 新事物在参与现有事务之前是否需要对其进行校验
	 * 如果需要，则会严格的验证隔离级别，只读属性等
	 * handleExistingTransaction中会使用到
	 */
	private boolean validateExistingTransaction = false;

	/**参与事务失败后是否将现有事务全局标记为仅回滚*/
	private boolean globalRollbackOnParticipationFailure = true;

	/**在事务全局标记为仅回滚的情况下是否尽早失败*/
	private boolean failEarlyOnGlobalRollbackOnly = false;

	/**提交失败时回滚*/
	private boolean rollbackOnCommitFailure = false;


	/**
	 * 通过此类中相应常量的名称设置事务同步
	 * Set the transaction synchronization by the name of the corresponding constant
	 * in this class, e.g. "SYNCHRONIZATION_ALWAYS".
	 * @param constantName name of the constant
	 * @see #SYNCHRONIZATION_ALWAYS
	 */
	public final void setTransactionSynchronizationName(String constantName) {
		setTransactionSynchronization(constants.asNumber(constantName).intValue());
	}

	/**
	 * 设置此事务管理器何时激活线程绑定事务同步支持。默认值为always;
	 * 请注意，不同的事务管理器不支持多个并发事务的事务同步。任何时候都只允许一个事务管理器激活它。
	 * Set when this transaction manager should activate the thread-bound
	 * transaction synchronization support. Default is "always".
	  <p>Note that transaction synchronization isn't supported for
	  multiple concurrent transactions by different transaction managers.
	  Only one transaction manager is allowed to activate it at any time.
	 * @see #SYNCHRONIZATION_ALWAYS
	 * @see #SYNCHRONIZATION_ON_ACTUAL_TRANSACTION
	 * @see #SYNCHRONIZATION_NEVER
	 * @see TransactionSynchronizationManager
	 * @see TransactionSynchronization
	 */
	public final void setTransactionSynchronization(int transactionSynchronization) {
		this.transactionSynchronization = transactionSynchronization;
	}

	/**
	 * 返回此事务管理器是否应激活线程绑定的事务同步支持。
	 * Return if this transaction manager should activate the thread-bound
	 * transaction synchronization support.
	 */
	public final int getTransactionSynchronization() {
		return this.transactionSynchronization;
	}

	/**
	 * Specify the default timeout that this transaction manager should apply
	 * if there is no timeout specified at the transaction level, in seconds.
	 * <p>Default is the underlying transaction infrastructure's default timeout,
	 * e.g. typically 30 seconds in case of a JTA provider, indicated by the
	 * {@code TransactionDefinition.TIMEOUT_DEFAULT} value.
	 * @see org.springframework.transaction.TransactionDefinition#TIMEOUT_DEFAULT
	 */
	public final void setDefaultTimeout(int defaultTimeout) {
		if (defaultTimeout < TransactionDefinition.TIMEOUT_DEFAULT) {
			throw new InvalidTimeoutException("Invalid default timeout", defaultTimeout);
		}
		this.defaultTimeout = defaultTimeout;
	}

	/**
	 * Return the default timeout that this transaction manager should apply
	 * if there is no timeout specified at the transaction level, in seconds.
	 * <p>Returns {@code TransactionDefinition.TIMEOUT_DEFAULT} to indicate
	 * the underlying transaction infrastructure's default timeout.
	 */
	public final int getDefaultTimeout() {
		return this.defaultTimeout;
	}

	/**
	 * Set whether nested transactions are allowed. Default is "false".
	 * <p>Typically initialized with an appropriate default by the
	 * concrete transaction manager subclass.
	 */
	public final void setNestedTransactionAllowed(boolean nestedTransactionAllowed) {
		this.nestedTransactionAllowed = nestedTransactionAllowed;
	}

	/**
	 * Return whether nested transactions are allowed.
	 */
	public final boolean isNestedTransactionAllowed() {
		return this.nestedTransactionAllowed;
	}

	/**
	 * 设置是否在参与现有事务之前对其进行验证。
	 * 当参与现有事务时（例如，PROPAGATION_REQUIRES or PROPAGATION_SUPPORTS遇到现有事务），
	 * 外部事物特征将适用于内部事务范围，校验将检测内部事务定义的不兼容的隔离级别和只读属性，
	 * 并通过抛出异常来拒绝参与。
	 * 默认值为false,粗暴的忽略内部事务的设置，简单的用外部事物的特征覆盖内部事务，
	 * 如果将此标志设置为true,将会执行严格的验证；
	 *
	 * Set whether existing transactions should be validated before participating
	 * in them.
	 * <p>When participating in an existing transaction (e.g. with
	 * PROPAGATION_REQUIRED or PROPAGATION_SUPPORTS encountering an existing
	 * transaction), this outer transaction's characteristics will apply even
	 * to the inner transaction scope. Validation will detect incompatible
	 * isolation level and read-only settings on the inner transaction definition
	 * and reject participation accordingly through throwing a corresponding exception.
	 * <p>Default is "false", leniently ignoring inner transaction settings,
	 * simply overriding them with the outer transaction's characteristics.
	 * Switch this flag to "true" in order to enforce strict validation.
	 * @since 2.5.1
	 */
	public final void setValidateExistingTransaction(boolean validateExistingTransaction) {
		this.validateExistingTransaction = validateExistingTransaction;
	}

	/**
	 * 返回 是否在参与现有事务之前对其进行验证。
	 * Return whether existing transactions should be validated before participating
	 * in them.
	 * @since 2.5.1
	 */
	public final boolean isValidateExistingTransaction() {
		return this.validateExistingTransaction;
	}

	/**
	 * Set whether to globally mark an existing transaction as rollback-only
	 * after a participating transaction failed.
	 * <p>Default is "true": If a participating transaction (e.g. with
	 * PROPAGATION_REQUIRED or PROPAGATION_SUPPORTS encountering an existing
	 * transaction) fails, the transaction will be globally marked as rollback-only.
	 * The only possible outcome of such a transaction is a rollback: The
	 * transaction originator <i>cannot</i> make the transaction commit anymore.
	 * <p>Switch this to "false" to let the transaction originator make the rollback
	 * decision. If a participating transaction fails with an exception, the caller
	 * can still decide to continue with a different path within the transaction.
	 * However, note that this will only work as long as all participating resources
	 * are capable of continuing towards a transaction commit even after a data access
	 * failure: This is generally not the case for a Hibernate Session, for example;
	 * neither is it for a sequence of JDBC insert/update/delete operations.
	 * <p><b>Note:</b>This flag only applies to an explicit rollback attempt for a
	 * subtransaction, typically caused by an exception thrown by a data access operation
	 * (where TransactionInterceptor will trigger a {@code PlatformTransactionManager.rollback()}
	 * call according to a rollback rule). If the flag is off, the caller can handle the exception
	 * and decide on a rollback, independent of the rollback rules of the subtransaction.
	 * This flag does, however, <i>not</i> apply to explicit {@code setRollbackOnly}
	 * calls on a {@code TransactionStatus}, which will always cause an eventual
	 * global rollback (as it might not throw an exception after the rollback-only call).
	 * <p>The recommended solution for handling failure of a subtransaction
	 * is a "nested transaction", where the global transaction can be rolled
	 * back to a savepoint taken at the beginning of the subtransaction.
	 * PROPAGATION_NESTED provides exactly those semantics; however, it will
	 * only work when nested transaction support is available. This is the case
	 * with DataSourceTransactionManager, but not with JtaTransactionManager.
	 * @see #setNestedTransactionAllowed
	 * @see org.springframework.transaction.jta.JtaTransactionManager
	 */
	public final void setGlobalRollbackOnParticipationFailure(boolean globalRollbackOnParticipationFailure) {
		this.globalRollbackOnParticipationFailure = globalRollbackOnParticipationFailure;
	}

	/**
	 * 返回参与事务失败后是否将现有事务全局标记为仅回滚。
	 * Return whether to globally mark an existing transaction as rollback-only
	 * after a participating transaction failed.
	 */
	public final boolean isGlobalRollbackOnParticipationFailure() {
		return this.globalRollbackOnParticipationFailure;
	}

	/**
	 * Set whether to fail early in case of the transaction being globally marked
	 * as rollback-only.
	 * <p>Default is "false", only causing an UnexpectedRollbackException at the
	 * outermost transaction boundary. Switch this flag on to cause an
	 * UnexpectedRollbackException as early as the global rollback-only marker
	 * has been first detected, even from within an inner transaction boundary.
	 * <p>Note that, as of Spring 2.0, the fail-early behavior for global
	 * rollback-only markers has been unified: All transaction managers will by
	 * default only cause UnexpectedRollbackException at the outermost transaction
	 * boundary. This allows, for example, to continue unit tests even after an
	 * operation failed and the transaction will never be completed. All transaction
	 * managers will only fail earlier if this flag has explicitly been set to "true".
	 * @since 2.0
	 * @see org.springframework.transaction.UnexpectedRollbackException
	 */
	public final void setFailEarlyOnGlobalRollbackOnly(boolean failEarlyOnGlobalRollbackOnly) {
		this.failEarlyOnGlobalRollbackOnly = failEarlyOnGlobalRollbackOnly;
	}

	/**
	 * 返回在事务全局标记为仅回滚的情况下是否尽早失败
	 *
	 * Return whether to fail early in case of the transaction being globally marked
	 * as rollback-only.
	 * @since 2.0
	 */
	public final boolean isFailEarlyOnGlobalRollbackOnly() {
		return this.failEarlyOnGlobalRollbackOnly;
	}

	/**
	 * Set whether {@code doRollback} should be performed on failure of the
	 * {@code doCommit} call. Typically not necessary and thus to be avoided,
	 * as it can potentially override the commit exception with a subsequent
	 * rollback exception.
	 * <p>Default is "false".
	 * @see #doCommit
	 * @see #doRollback
	 */
	public final void setRollbackOnCommitFailure(boolean rollbackOnCommitFailure) {
		this.rollbackOnCommitFailure = rollbackOnCommitFailure;
	}

	/**
	 * 是否提交失败时回滚
	 * Return whether {@code doRollback} should be performed on failure of the
	 * {@code doCommit} call.
	 */
	public final boolean isRollbackOnCommitFailure() {
		return this.rollbackOnCommitFailure;
	}


	//---------------------------------------------------------------------
	// Implementation of PlatformTransactionManager
	//---------------------------------------------------------------------

	/**
	 * This implementation handles propagation behavior. Delegates to
	 * {@code doGetTransaction}, {@code isExistingTransaction}
	 * and {@code doBegin}.
	 * @see #doGetTransaction
	 * @see #isExistingTransaction
	 * @see #doBegin
	 */
	@Override
	public final TransactionStatus getTransaction(@Nullable TransactionDefinition definition) throws TransactionException {
		// 1.获取事务对象
		// 获取事务，doGetTransaction是个抽象方法， 此方法交由具体的事务管理器实现
		// 这里会创建一个新的事务对象，从TransactionSynchronizationManager中获取当前线程持有的数据库连接的句柄,并设置到事务对象中返回
		Object transaction = doGetTransaction();

		// Cache debug flag to avoid repeated checks.
		boolean debugEnabled = logger.isDebugEnabled();

		if (definition == null) {
			// Use defaults if no transaction definition given.
			// 如果传入的事务定义信息对象为空，就创建默认的事务定义，隔离级别，传播属性等信息都为默认值
			definition = new DefaultTransactionDefinition();
		}

		// 2.如果事务是一个已存在的事务
		// 判断依据为：如果事务对象中的连接句柄不为null && 事务对象中的连接句柄处于事务活跃状态 ==>这个事务是一个已经存在的事务
		if (isExistingTransaction(transaction)) {
			// 当前线程关联的数据库连接存在且事务处于激活状态，那么当前事务会根据事务传播机制来处理当前事务
			// Existing transaction found -> check propagation behavior to find out how to behave.
			return handleExistingTransaction(definition, transaction, debugEnabled);
		}

		// 3.事务是一个新事物

		// 3.1 校验超时时间
		// Check definition settings for new transaction.
		if (definition.getTimeout() < TransactionDefinition.TIMEOUT_DEFAULT) {
			throw new InvalidTimeoutException("Invalid transaction timeout", definition.getTimeout());
		}

		// 3.2 处理传播属性
		// No existing transaction found -> check propagation behavior to find out how to proceed.
		// PROPAGATION_MANDATORY     当前有事务，就加入事务，没有就抛出异常
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_MANDATORY) {
			throw new IllegalTransactionStateException(
					"No existing transaction found for transaction marked with propagation 'mandatory'");
		}
		// PROPAGATION_REQUIRED      当前有事务，就加入这个事务，没有事务，就新建一个事务
		// PROPAGATION_REQUIRES_NEW  新建一个事务执行，如果当前有事务，就把当前的事务挂起
		// PROPAGATION_NESTED        当前有事务，就嵌套执行，当前无事务，就新建一个事务执行
		else if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED ||
				definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW ||
				definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
			// 由于当前无事务，所以，上面三种传播属性，都没有需要暂挂的事务
			SuspendedResourcesHolder suspendedResources = suspend(null);
			if (debugEnabled) {
				logger.debug("Creating new transaction with name [" + definition.getName() + "]: " + definition);
			}
			try {
				// 返回此事务管理器是否应激活线程绑定事务同步支持
				boolean newSynchronization = (getTransactionSynchronization() != SYNCHRONIZATION_NEVER);
				// 由于当前无事务，所以，上面三种传播属性，都是需要创建一个新的事务
				DefaultTransactionStatus status = newTransactionStatus(
						definition, transaction, true, newSynchronization, debugEnabled, suspendedResources);
				// 开启事务
				doBegin(transaction, definition);
				// 初始化事务同步
				prepareSynchronization(status, definition);
				return status;
			}
			catch (RuntimeException | Error ex) {
				resume(null, suspendedResources);
				throw ex;
			}
		}
		else {
			// 创建一个空事务：没有真实的事务，但是可能需要同步
			// Create "empty" transaction: no actual transaction, but potentially synchronization.
			if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT && logger.isWarnEnabled()) {
				logger.warn("Custom isolation level specified but no actual transaction initiated; " +
						"isolation level will effectively be ignored: " + definition);
			}
			// 返回此事务管理器是否应激活线程绑定事务同步支持
			boolean newSynchronization = (getTransactionSynchronization() == SYNCHRONIZATION_ALWAYS);
			// 创建新的DefaultTransactionStatus实例，并准备事务同步
			return prepareTransactionStatus(definition, null, true, newSynchronization, debugEnabled, null);
		}
	}

	/**
	 * 给一个已经存在的事务创建事务状态对象 TransactionStatus
	 * Create a TransactionStatus for an existing transaction.
	 *
	 * @param definition 事务定义信息
	 * @param transaction getTransaction()返回的事务对象
	 * @param debugEnabled
	 * @return
	 * @throws TransactionException
	 */
	private TransactionStatus handleExistingTransaction(
			TransactionDefinition definition, Object transaction, boolean debugEnabled)
			throws TransactionException {
		// 1.处理传播行为

		// 1.1 PROPAGATION_NEVER 在无事务状态下执行，如果当前有事务，会抛出异常
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NEVER) {
			throw new IllegalTransactionStateException(
					"Existing transaction found for transaction marked with propagation 'never'");
		}

		// 1.2 PROPAGATION_NOT_SUPPORTED 在无事务状态下执行，如果当前有事务，就把当前的事务挂起
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NOT_SUPPORTED) {
			if (debugEnabled) {
				logger.debug("Suspending current transaction");
			}
			// 挂起当前事务
			Object suspendedResources = suspend(transaction);
			// 是否开启新的事务同步
			boolean newSynchronization = (getTransactionSynchronization() == SYNCHRONIZATION_ALWAYS);
			// 创建事务状态实例+准备事务同步
			return prepareTransactionStatus(
					definition, null, false, newSynchronization, debugEnabled, suspendedResources);
		}

		// 1.3 PROPAGATION_REQUIRES_NEW 新建一个事务执行，如果当前有事务，就把当前的事务挂起
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
			if (debugEnabled) {
				logger.debug("Suspending current transaction, creating new transaction with name [" +
						definition.getName() + "]");
			}
			SuspendedResourcesHolder suspendedResources = suspend(transaction);
			try {
				// 是否开启新的事务同步
				boolean newSynchronization = (getTransactionSynchronization() != SYNCHRONIZATION_NEVER);
				// 创建事务状态实例
				DefaultTransactionStatus status = newTransactionStatus(
						definition, transaction, true, newSynchronization, debugEnabled, suspendedResources);
				// 开启事务
				doBegin(transaction, definition);
				// 初始化事务同步
				prepareSynchronization(status, definition);
				return status;
			}
			catch (RuntimeException | Error beginEx) {
				// 出现异常，内部事务开启失败后，恢复外部事务
				resumeAfterBeginException(transaction, suspendedResources, beginEx);
				throw beginEx;
			}
		}

		// 1.4 PROPAGATION_NESTED  当前有事务，就新建一个事务，嵌套执行，当前无事务，就新建一个事务执行
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
			if (!isNestedTransactionAllowed()) {
				throw new NestedTransactionNotSupportedException(
						"Transaction manager does not allow nested transactions by default - " +
						"specify 'nestedTransactionAllowed' property with value 'true'");
			}
			if (debugEnabled) {
				logger.debug("Creating nested transaction with name [" + definition.getName() + "]");
			}
			// 如果对嵌套事务使用保存点
			if (useSavepointForNestedTransaction()) {

				// 通过TransactionStatus实现的SavepointManager的api，在已存在的spring管理的事务中创建一个保存点。
				// 通常使用JDBC3.0的安全点，从不激活spring的同步。

				// Create savepoint within existing Spring-managed transaction,
				// through the SavepointManager API implemented by TransactionStatus.
				// Usually uses JDBC 3.0 savepoints. Never activates Spring synchronization.

				// 创建事务状态实例+准备事务同步
				DefaultTransactionStatus status =
						prepareTransactionStatus(definition, transaction, false, false, debugEnabled, null);
				// 创建一个保存点并将其保存在事务中。
				status.createAndHoldSavepoint();
				return status;
			}
			// 如果不对嵌套事务使用保存点
			else {
				// 通过嵌套的 begin and commit/rollback 调用进行的嵌套事务
				// 通常仅用于JTA：如果有预先存在的JTA事务，则可以在此处激活Spring同步
				// Nested transaction through nested begin and commit/rollback calls.
				// Usually only for JTA: Spring synchronization might get activated here
				// in case of a pre-existing JTA transaction.

				// 是否开启新的事务同步
				boolean newSynchronization = (getTransactionSynchronization() != SYNCHRONIZATION_NEVER);
				// 创建一个新的TransactionStatus
				DefaultTransactionStatus status = newTransactionStatus(
						definition, transaction, true, newSynchronization, debugEnabled, null);
				// 开启事务
				doBegin(transaction, definition);
				// 初始化事务同步
				prepareSynchronization(status, definition);
				return status;
			}
		}

		// Assumably PROPAGATION_SUPPORTS or PROPAGATION_REQUIRED.
		if (debugEnabled) {
			logger.debug("Participating in existing transaction");
		}

		// 2 如果新事物在参与现有事务之前需要对其进行校验
		// 传播属性支持的情况下，严格校验内外事务的隔离级别，只读属性等是否相同是否支持
		if (isValidateExistingTransaction()) {
			// 2.1 校验隔离级别
			// 如果新事务定义中隔离级别不是默认的事务隔离级别
			if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {
				// 从事务同步管理器中获取当前事务的隔离级别
				Integer currentIsolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
				// 如果当前事务的隔离级别为null 或者 从事务管理器获取的当前事务的隔离级别和新事务定义信息中的隔离级别不相同，会抛出非法事务状态异常
				if (currentIsolationLevel == null || currentIsolationLevel != definition.getIsolationLevel()) {
					Constants isoConstants = DefaultTransactionDefinition.constants;
					throw new IllegalTransactionStateException("Participating transaction with definition [" +
							definition + "] specifies isolation level which is incompatible with existing transaction: " +
							(currentIsolationLevel != null ?
									isoConstants.toCode(currentIsolationLevel, DefaultTransactionDefinition.PREFIX_ISOLATION) :
									"(unknown)"));
				}
			}
			// 2.2 校验只读属性
			// 如果事务定义信息中不是只读
			if (!definition.isReadOnly()) {
				// 但是事务管理器获取的只读的，会抛出非法事务状态异常
				if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
					throw new IllegalTransactionStateException("Participating transaction with definition [" +
							definition + "] is not marked as read-only but existing transaction is");
				}
			}
		}
		// 是否开启新的事务同步
		boolean newSynchronization = (getTransactionSynchronization() != SYNCHRONIZATION_NEVER);
		// 创建事务状态实例+准备事务同步
		return prepareTransactionStatus(definition, transaction, false, newSynchronization, debugEnabled, null);
	}

	/**
	 * 为给定的参数创建一个新的TransactionStatus，并根据需要初始化事务同步。
	 * Create a new TransactionStatus for the given arguments,
	 * also initializing transaction synchronization as appropriate.
	 * @see #newTransactionStatus
	 * @see #prepareTransactionStatus
	 */
	protected final DefaultTransactionStatus prepareTransactionStatus(
			TransactionDefinition definition, @Nullable Object transaction, boolean newTransaction,
			boolean newSynchronization, boolean debug, @Nullable Object suspendedResources) {

		// 1.创建新的DefaultTransactionStatus实例
		DefaultTransactionStatus status = newTransactionStatus(
				definition, transaction, newTransaction, newSynchronization, debug, suspendedResources);
		// 2.准备事务同步
		prepareSynchronization(status, definition);
		return status;
	}

	/**
	 * 为给定的参数创建一个新的TransactionStatus，并根据需要初始化事务同步。
	 * Create a TransactionStatus instance for the given arguments.
	 * @param definition 当前事务定义信息
	 * @param transaction 当前事务对象
	 * @param newTransaction 是否是新事务
	 * @param newSynchronization 是否为指定事务开启新的事务同步
	 * @param debug
	 * @param suspendedResources 此事务已经被暂挂的资源持有者（如果有）
	 * @return
	 */
	protected DefaultTransactionStatus newTransactionStatus(
			TransactionDefinition definition, @Nullable Object transaction, boolean newTransaction,
			boolean newSynchronization, boolean debug, @Nullable Object suspendedResources) {

		// 是否是实际的新的事务同步
		// 如果是新事务 && 如果当前线程的事务同步不是活跃状态 ===》为指定事务开启一个新的事务同步
		boolean actualNewSynchronization = newSynchronization &&
				!TransactionSynchronizationManager.isSynchronizationActive();
		return new DefaultTransactionStatus(
				transaction, newTransaction, actualNewSynchronization,
				definition.isReadOnly(), debug, suspendedResources);
	}

	/**
	 * 根据需要初始化事务同步
	 * Initialize transaction synchronization as appropriate.
	 */
	protected void prepareSynchronization(DefaultTransactionStatus status, TransactionDefinition definition) {
		if (status.isNewSynchronization()) {
			// 设置当前是否有实际的活跃事务
			TransactionSynchronizationManager.setActualTransactionActive(status.hasTransaction());
			// 设置隔离级别 如果定义信息中的隔离级别不是默认的隔离级别，则使用定义信息中的隔离级别，否则为null
			TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
					definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT ?
							definition.getIsolationLevel() : null);
			// 只读属性
			TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
			// 事务名称
			TransactionSynchronizationManager.setCurrentTransactionName(definition.getName());
			// 激活当前线程的事务同步 初始化TransactionSynchronizationManager的synchronizations集合
			// ThreadLocal<Set<TransactionSynchronization>> synchronizations
			TransactionSynchronizationManager.initSynchronization();
		}
	}

	/**
	 * Determine the actual timeout to use for the given definition.
	 * Will fall back to this manager's default timeout if the
	 * transaction definition doesn't specify a non-default value.
	 * @param definition the transaction definition
	 * @return the actual timeout to use
	 * @see org.springframework.transaction.TransactionDefinition#getTimeout()
	 * @see #setDefaultTimeout
	 */
	protected int determineTimeout(TransactionDefinition definition) {
		if (definition.getTimeout() != TransactionDefinition.TIMEOUT_DEFAULT) {
			return definition.getTimeout();
		}
		return getDefaultTimeout();
	}


	/**
	 * 挂起事务
	 * （挂起事务，其实就是把当前线程中绑定的事务相关信息，都保存到SuspendedResourcesHolder中返回，
	 * 后面要用时，根据这个存储的暂挂资源信息，来恢复被挂起的事务。
	 * 然后清空当前线程中的事务对象，方便其他事务写进来）
	 * 挂起指定的事务，首先暂停事务同步，然后委托给doSuspend模板方法
	 * 当事务对象为null,如果当前有活跃的同步，就挂起当前活跃的同步
	 * Suspend the given transaction. Suspends transaction synchronization first,
	 * then delegates to the {@code doSuspend} template method.
	 *
	 * 前事务对象（或{@code null}仅暂停活动同步，如果有的话）
	 * @param transaction the current transaction object
	  (or {@code null} to just suspend active synchronizations, if any)
	 * 回持有挂起资源的对象
	 * @return an object that holds suspended resources
	 * 如果事务和同步都没有激活，就返回null
	 * (or {@code null} if neither transaction nor synchronization active)
	 * @see #doSuspend
	 * @see #resume
	 */
	@Nullable
	protected final SuspendedResourcesHolder suspend(@Nullable Object transaction) throws TransactionException {
		// 如果当前线程事务同步是活跃状态
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			// 暂停当前的所有同步，并禁用当前线程的事务同步 返回挂起事务同步的对象列表        要暂挂的事务同步（同步）
			List<TransactionSynchronization> suspendedSynchronizations = doSuspendSynchronization();
			try {
				Object suspendedResources = null;
				// 如果传入事务不为null
				if (transaction != null) {
					// 挂起当前事务资源，返回暂挂资源对象（比如resourceHoler，连接句柄）	  要暂挂的资源（资源）
					suspendedResources = doSuspend(transaction);
				}
				// 重置事务同步管理器的数据
				// 获取当前事务同步管理器中当前事物的名称，只读属性，隔离级别，是否有实际的活跃事务等信息，赋值给暂挂资源持有对象，然后重置     要暂挂的一些状态和属性（状态属性）
				String name = TransactionSynchronizationManager.getCurrentTransactionName();
				TransactionSynchronizationManager.setCurrentTransactionName(null);
				boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
				TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
				Integer isolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
				TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
				//返回当前是否有实际的活跃事务
				boolean wasActive = TransactionSynchronizationManager.isActualTransactionActive();
				//设置当前是否有实际的活跃事务 false
				TransactionSynchronizationManager.setActualTransactionActive(false);
				// 返回持有暂挂资源的对象
				return new SuspendedResourcesHolder(
						suspendedResources, suspendedSynchronizations, name, readOnly, isolationLevel, wasActive);
			}
			// 如果挂起失败，就利用前面返回的挂起事务对象列表来恢复同步
			catch (RuntimeException | Error ex) {
				// doSuspend failed - original transaction is still active...
				doResumeSynchronization(suspendedSynchronizations);
				throw ex;
			}
		}
		else if (transaction != null) {
			// 如果线程事务同步未激活，但事务处于活跃状态，挂起当前事务资源，返回暂挂资源对象
			// 事务同步未激活，所以就不用从事务同步管理器中获取相关属性值
			// Transaction active but no synchronization active.
			Object suspendedResources = doSuspend(transaction);
			// 返回持有暂挂资源的对象
			return new SuspendedResourcesHolder(suspendedResources);
		}
		else {
			// 如果事务和同步均未激活，返回null
			// Neither transaction nor synchronization active.
			return null;
		}
	}

	/**
	 * 恢复指定的事务，先委派给doResume处理，然后恢复事务同步
	 * Resume the given transaction. Delegates to the {@code doResume}
	 * template method first, then resuming transaction synchronization.
	 * 当前事务对象
	 * @param transaction the current transaction object
	 * 由suspend方法返回的持有暂挂资源的对象，或者为null，如果有需要，用此对象恢复同步
	 * @param resourcesHolder the object that holds suspended resources,
	 * as returned by {@code suspend} (or {@code null} to just
	 * resume synchronizations, if any)
	 * @see #doResume
	 * @see #suspend
	 */
	protected final void resume(@Nullable Object transaction, @Nullable SuspendedResourcesHolder resourcesHolder)
			throws TransactionException {

		// 如果持有暂挂资源的对象不为null
		if (resourcesHolder != null) {
			// 获取暂挂资源
			Object suspendedResources = resourcesHolder.suspendedResources;
			if (suspendedResources != null) {
				// 如果暂挂资源不为null,用事务对象和暂挂资源信息去恢复资源，恢复同步
				doResume(transaction, suspendedResources);
			}
			// 获取暂停的同步
			List<TransactionSynchronization> suspendedSynchronizations = resourcesHolder.suspendedSynchronizations;
			if (suspendedSynchronizations != null) {
				// 重置同步信息
				//设置当前是否有实际的活跃事务
				TransactionSynchronizationManager.setActualTransactionActive(resourcesHolder.wasActive);
				TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(resourcesHolder.isolationLevel);
				TransactionSynchronizationManager.setCurrentTransactionReadOnly(resourcesHolder.readOnly);
				TransactionSynchronizationManager.setCurrentTransactionName(resourcesHolder.name);
				// 重新激活当前线程的事务同步，并且恢复所有指定的同步
				doResumeSynchronization(suspendedSynchronizations);
			}
		}
	}

	/**
	 * 内部事务开始失败后恢复外部事务。
	 * Resume outer transaction after inner transaction begin failed.
	 */
	private void resumeAfterBeginException(
			Object transaction, @Nullable SuspendedResourcesHolder suspendedResources, Throwable beginEx) {

		String exMessage = "Inner transaction begin exception overridden by outer transaction resume exception";
		try {
			// 恢复事务，并且恢复事务同步
			resume(transaction, suspendedResources);
		}
		catch (RuntimeException | Error resumeEx) {
			logger.error(exMessage, beginEx);
			throw resumeEx;
		}
	}

	/**
	 * 挂起所有当前同步，并停用当前线程的事务同步。
	 *
	 * Suspend all current synchronizations and deactivate transaction
	 * synchronization for the current thread.
	 *
	 * 挂起的TransactionSynchronization对象的列表
	 * @return the List of suspended TransactionSynchronization objects
	 */
	private List<TransactionSynchronization> doSuspendSynchronization() {
		List<TransactionSynchronization> suspendedSynchronizations =
				TransactionSynchronizationManager.getSynchronizations();
		for (TransactionSynchronization synchronization : suspendedSynchronizations) {
			synchronization.suspend();
		}
		TransactionSynchronizationManager.clearSynchronization();
		return suspendedSynchronizations;
	}

	/**
	 * 恢复同步
	 * 恢复当前线程的事务同步，并且恢复所有给定的事务同步
	 * Reactivate transaction synchronization for the current thread
	 * and resume all given synchronizations.
	 * @param suspendedSynchronizations a List of TransactionSynchronization objects
	 */
	private void doResumeSynchronization(List<TransactionSynchronization> suspendedSynchronizations) {
		//激活当前线程的事务同步 初始化TransactionSynchronizationManager的synchronizations集合
		//ThreadLocal<Set<TransactionSynchronization>> synchronizations
		TransactionSynchronizationManager.initSynchronization();
		for (TransactionSynchronization synchronization : suspendedSynchronizations) {
			// 恢复资源：将资源重新绑定到TransactionSynchronizationManager
			synchronization.resume();
			// 恢复同步：注册此同步，将此同步添加到TransactionSynchronizationManager的synchronizations集合
			TransactionSynchronizationManager.registerSynchronization(synchronization);
		}
	}


	/**
	 * 提交的实现，用来处理参与现有事务的提交和程序化的回滚请求
	 * This implementation of commit handles participating in existing
	 * transactions and programmatic rollback requests.
	 * Delegates to {@code isRollbackOnly}, {@code doCommit}
	 * and {@code rollback}.
	 * @see org.springframework.transaction.TransactionStatus#isRollbackOnly()
	 * @see #doCommit
	 * @see #rollback
	 */
	@Override
	public final void commit(TransactionStatus status) throws TransactionException {
		// 检查事务状态
		if (status.isCompleted()) {
			throw new IllegalTransactionStateException(
					"Transaction is already completed - do not call commit or rollback more than once per transaction");
		}

		DefaultTransactionStatus defStatus = (DefaultTransactionStatus) status;
		// 如果仅本地回滚
		if (defStatus.isLocalRollbackOnly()) {
			if (defStatus.isDebug()) {
				logger.debug("Transactional code has requested rollback");
			}
			//如果回滚
			processRollback(defStatus, false);
			return;
		}

		// 全局事务标记为仅回滚时不应该commit && 全局标记为仅回滚
		if (!shouldCommitOnGlobalRollbackOnly() && defStatus.isGlobalRollbackOnly()) {
			if (defStatus.isDebug()) {
				logger.debug("Global transaction is marked as rollback-only but transactional code requested commit");
			}
			//如理回滚
			processRollback(defStatus, true);
			return;
		}

		processCommit(defStatus);
	}

	/**
	 * 处理实际的事务提交
	 * 仅回滚标记已经检查过了
	 * Process an actual commit.
	 * Rollback-only flags have already been checked and applied.
	 * @param status object representing the transaction
	 * @throws TransactionException in case of commit failure
	 */
	private void processCommit(DefaultTransactionStatus status) throws TransactionException {
		try {
			boolean beforeCompletionInvoked = false;

			try {
				//是否有意外的回滚
				boolean unexpectedRollback = false;
				//准备提交
				prepareForCommit(status);
				//触发beforeCommit回调
				triggerBeforeCommit(status);
				//触发beforeCompletion回调
				triggerBeforeCompletion(status);
				beforeCompletionInvoked = true;

				//如果事务有保存点
				if (status.hasSavepoint()) {
					if (status.isDebug()) {
						logger.debug("Releasing transaction savepoint");
					}
					//设置unexpectedRollback状态
					//如果全局仅回滚，则设置unexpectedRollback=true，否则为false
					unexpectedRollback = status.isGlobalRollbackOnly();
					//释放掉该事务保存点
					status.releaseHeldSavepoint();
				}
				//如果有实际的事务处于活跃状态
				else if (status.isNewTransaction()) {
					if (status.isDebug()) {
						logger.debug("Initiating transaction commit");
					}
					//设置unexpectedRollback状态
					//如果全局仅回滚，则设置unexpectedRollback=true，否则为false
					unexpectedRollback = status.isGlobalRollbackOnly();
					//提交事务
					doCommit(status);
				}
				//如果设置了 事务全局标记为仅回滚的情况下尽早失败
				else if (isFailEarlyOnGlobalRollbackOnly()) {
					//设置unexpectedRollback状态
					//如果全局仅回滚，则设置unexpectedRollback=true，否则为false
					unexpectedRollback = status.isGlobalRollbackOnly();
				}

				// 如果我们有一个仅全局回滚的标记，但仍未从提交中获得相应的异常，则抛出UnexpectedRollbackException。
				// Throw UnexpectedRollbackException if we have a global rollback-only
				// marker but still didn't get a corresponding exception from commit.
				if (unexpectedRollback) {
					throw new UnexpectedRollbackException(
							"Transaction silently rolled back because it has been marked as rollback-only");
				}
			}
			catch (UnexpectedRollbackException ex) {
				//触发afterCompletion回调，仅仅会被commit调用此方法
				// can only be caused by doCommit
				triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK);
				throw ex;
			}
			catch (TransactionException ex) {
				// can only be caused by doCommit
				// 如果标记为 提交失败时回滚
				if (isRollbackOnCommitFailure()) {
					//回滚事务
					doRollbackOnCommitException(status, ex);
				}
				else {
					triggerAfterCompletion(status, TransactionSynchronization.STATUS_UNKNOWN);
				}
				throw ex;
			}
			catch (RuntimeException | Error ex) {
				if (!beforeCompletionInvoked) {
					triggerBeforeCompletion(status);
				}
				doRollbackOnCommitException(status, ex);
				throw ex;
			}

			// Trigger afterCommit callbacks, with an exception thrown there
			// propagated to callers but the transaction still considered as committed.
			try {
				triggerAfterCommit(status);
			}
			finally {
				triggerAfterCompletion(status, TransactionSynchronization.STATUS_COMMITTED);
			}

		}
		finally {
			cleanupAfterCompletion(status);
		}
	}

	/**
	 * 回滚的实现，用来处理参与现有事务的回滚
	 * This implementation of rollback handles participating in existing
	 * transactions. Delegates to {@code doRollback} and
	 * {@code doSetRollbackOnly}.
	 * @see #doRollback
	 * @see #doSetRollbackOnly
	 */
	@Override
	public final void rollback(TransactionStatus status) throws TransactionException {
		// 如果事务完成，则抛出异常
		if (status.isCompleted()) {
			throw new IllegalTransactionStateException(
					"Transaction is already completed - do not call commit or rollback more than once per transaction");
		}

		DefaultTransactionStatus defStatus = (DefaultTransactionStatus) status;
		processRollback(defStatus, false);
	}

	/**
	 * 处理实际的回滚。
	 * 已经检查完成标志。
	 * Process an actual rollback.
	 * The completed flag has already been checked.
	 * @param status object representing the transaction
	 * @throws TransactionException in case of rollback failure
	 */
	private void processRollback(DefaultTransactionStatus status, boolean unexpected) {
		try {
			// 是否是意外的回滚
			boolean unexpectedRollback = unexpected;

			try {
				triggerBeforeCompletion(status);

				// 如果该事务有保存点
				if (status.hasSavepoint()) {
					if (status.isDebug()) {
						logger.debug("Rolling back transaction to savepoint");
					}
					// 回滚该事务至保存点
					status.rollbackToHeldSavepoint();
				}
				// 如果该事务是个新事务
				else if (status.isNewTransaction()) {
					if (status.isDebug()) {
						logger.debug("Initiating transaction rollback");
					}
					// 事务的实际回滚（数据库连接的回滚）
					doRollback(status);
				}
				else {
					// Participating in larger transaction
					// 如果有活跃的事务存在
					if (status.hasTransaction()) {
						// 如果事务状态为设置为 仅回滚 || 参与事务失败后将现有事务全局标记为仅回滚
						if (status.isLocalRollbackOnly() || isGlobalRollbackOnParticipationFailure()) {
							if (status.isDebug()) {
								logger.debug("Participating transaction failed - marking existing transaction as rollback-only");
							}
							//设置事务状态为仅回滚
							doSetRollbackOnly(status);
						}
						else {
							if (status.isDebug()) {
								logger.debug("Participating transaction failed - letting transaction originator decide on rollback");
							}
						}
					}
					else {
						logger.debug("Should roll back transaction but cannot - no transaction available");
					}
					// 仅在要求我们提前失败的情况下，意外的回滚才有意义
					// Unexpected rollback only matters here if we're asked to fail early
					// 如果没有设置 在事务全局标记为仅回滚的情况下尽早失败，则意外的错误不会导致意外的回滚
					if (!isFailEarlyOnGlobalRollbackOnly()) {
						unexpectedRollback = false;
					}
				}
			}
			catch (RuntimeException | Error ex) {
				triggerAfterCompletion(status, TransactionSynchronization.STATUS_UNKNOWN);
				throw ex;
			}

			triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK);

			// 如果我们有一个仅全局回滚的标记，则引发UnexpectedRollbackException
			// Raise UnexpectedRollbackException if we had a global rollback-only marker
			if (unexpectedRollback) {
				throw new UnexpectedRollbackException(
						"Transaction rolled back because it has been marked as rollback-only");
			}
		}
		finally {
			cleanupAfterCompletion(status);
		}
	}

	/**
	 * 调用{@code doRollback}，以正确处理回滚异常。
	 * Invoke {@code doRollback}, handling rollback exceptions properly.
	 * @param status object representing the transaction 当前事务对象
	 * @param ex the thrown application exception or error
	 * @throws TransactionException in case of rollback failure
	 * @see #doRollback
	 */
	private void doRollbackOnCommitException(DefaultTransactionStatus status, Throwable ex) throws TransactionException {
		try {
			// 如果是个新事务
			if (status.isNewTransaction()) {
				if (status.isDebug()) {
					logger.debug("Initiating transaction rollback after commit exception", ex);
				}
				// 回滚事务
				doRollback(status);
			}
			// 如果事务处于活跃状态 && 参与事务失败后将现有事务全局标记为仅回滚
			else if (status.hasTransaction() && isGlobalRollbackOnParticipationFailure()) {
				if (status.isDebug()) {
					logger.debug("Marking existing transaction as rollback-only after commit exception", ex);
				}
				// 设置事务为仅回滚
				doSetRollbackOnly(status);
			}
		}
		catch (RuntimeException | Error rbex) {
			logger.error("Commit exception overridden by rollback exception", ex);
			triggerAfterCompletion(status, TransactionSynchronization.STATUS_UNKNOWN);
			throw rbex;
		}
		triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK);
	}


	/**
	 * 触发{@code beforeCommit}回调
	 * Trigger {@code beforeCommit} callbacks.
	 * @param status object representing the transaction
	 */
	protected final void triggerBeforeCommit(DefaultTransactionStatus status) {
		if (status.isNewSynchronization()) {
			if (status.isDebug()) {
				logger.trace("Triggering beforeCommit synchronization");
			}
			TransactionSynchronizationUtils.triggerBeforeCommit(status.isReadOnly());
		}
	}

	/**
	 * 触发{@code beforeCompletion}回调
	 * Trigger {@code beforeCompletion} callbacks.
	 * @param status object representing the transaction
	 */
	protected final void triggerBeforeCompletion(DefaultTransactionStatus status) {
		if (status.isNewSynchronization()) {
			if (status.isDebug()) {
				logger.trace("Triggering beforeCompletion synchronization");
			}
			TransactionSynchronizationUtils.triggerBeforeCompletion();
		}
	}

	/**
	 * Trigger {@code afterCommit} callbacks.
	 * @param status object representing the transaction
	 */
	private void triggerAfterCommit(DefaultTransactionStatus status) {
		if (status.isNewSynchronization()) {
			if (status.isDebug()) {
				logger.trace("Triggering afterCommit synchronization");
			}
			TransactionSynchronizationUtils.triggerAfterCommit();
		}
	}

	/**
	 * 触发{@code afterCompletion}回调
	 * Trigger {@code afterCompletion} callbacks.
	 * @param status object representing the transaction
	 * @param completionStatus completion status according to TransactionSynchronization constants
	 */
	private void triggerAfterCompletion(DefaultTransactionStatus status, int completionStatus) {
		// 如果是新的事务同步
		if (status.isNewSynchronization()) {
			// 获取所有的事务同步
			List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
			// 清理事务同步
			TransactionSynchronizationManager.clearSynchronization();

			// 当前范围内没有事务||此事务是个新事务
			if (!status.hasTransaction() || status.isNewTransaction()) {
				if (status.isDebug()) {
					logger.trace("Triggering afterCompletion synchronization");
				}
				// 当前范围内没有事务||此事务是个新事务->立即调用afterCompletion回调

				// No transaction or new transaction for the current scope ->
				// invoke the afterCompletion callbacks immediately
				//TODO
				invokeAfterCompletion(synchronizations, completionStatus);
			}
			else if (!synchronizations.isEmpty()) {
				// 我们参与的现有事务在此Spring事务管理器的范围之外进行控制->尝试向现有（JTA）事务注册一个afterCompletion回调
				// Existing transaction that we participate in, controlled outside
				// of the scope of this Spring transaction manager -> try to register
				// an afterCompletion callback with the existing (JTA) transaction.
				// TODO
				registerAfterCompletionWithExistingTransaction(status.getTransaction(), synchronizations);
			}
		}
	}

	/**
	 * Actually invoke the {@code afterCompletion} methods of the
	 * given Spring TransactionSynchronization objects.
	 * <p>To be called by this abstract manager itself, or by special implementations
	 * of the {@code registerAfterCompletionWithExistingTransaction} callback.
	 * @param synchronizations a List of TransactionSynchronization objects
	 * @param completionStatus the completion status according to the
	 * constants in the TransactionSynchronization interface
	 * @see #registerAfterCompletionWithExistingTransaction(Object, java.util.List)
	 * @see TransactionSynchronization#STATUS_COMMITTED
	 * @see TransactionSynchronization#STATUS_ROLLED_BACK
	 * @see TransactionSynchronization#STATUS_UNKNOWN
	 */
	protected final void invokeAfterCompletion(List<TransactionSynchronization> synchronizations, int completionStatus) {
		TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations, completionStatus);
	}

	/**
	 * Clean up after completion, clearing synchronization if necessary,
	 * and invoking doCleanupAfterCompletion.
	 * @param status object representing the transaction
	 * @see #doCleanupAfterCompletion
	 */
	private void cleanupAfterCompletion(DefaultTransactionStatus status) {
		status.setCompleted();
		if (status.isNewSynchronization()) {
			TransactionSynchronizationManager.clear();
		}
		if (status.isNewTransaction()) {
			doCleanupAfterCompletion(status.getTransaction());
		}
		if (status.getSuspendedResources() != null) {
			if (status.isDebug()) {
				logger.debug("Resuming suspended transaction after completion of inner transaction");
			}
			Object transaction = (status.hasTransaction() ? status.getTransaction() : null);
			resume(transaction, (SuspendedResourcesHolder) status.getSuspendedResources());
		}
	}


	//---------------------------------------------------------------------
	// Template methods to be implemented in subclasses
	//---------------------------------------------------------------------

	/**
	 * 返回当前事务状态的事务对象。
	 * 返回的对象通常将特定于具体的事务管理器实现，以可修改的方式携带对应的事务状态。
	 * 该对象将直接或作为DefaultTransactionStatus实例的一部分传递给其他模板方法（例如boBegin或者doCommit).
	 * 返回的对象应该包含有关任何现有事务的信息，即，在事务管理器上调用doGetTransaction之前已经开始的事务。
	 *
	 * 因此，doGetTransaction的实现通常将查找现有事务并将对应的状态存储在返回的事务对象中。
	 *
	 * Return a transaction object for the current transaction state.
	 * <p>The returned object will usually be specific to the concrete transaction
	 * manager implementation, carrying corresponding transaction state in a
	 * modifiable fashion. This object will be passed into the other template
	 * methods (e.g. doBegin and doCommit), either directly or as part of a
	 * DefaultTransactionStatus instance.
	 * <p>The returned object should contain information about any existing
	 * transaction, that is, a transaction that has already started before the
	 * current {@code getTransaction} call on the transaction manager.
	 * Consequently, a {@code doGetTransaction} implementation will usually
	 * look for an existing transaction and store corresponding state in the
	 * returned transaction object.
	 * @return the current transaction object 当前事务对象
	 * @throws org.springframework.transaction.CannotCreateTransactionException
	 * if transaction support is not available
	 * @throws TransactionException in case of lookup or system errors
	 * @see #doBegin
	 * @see #doCommit
	 * @see #doRollback
	 * @see DefaultTransactionStatus#getTransaction
	 */
	protected abstract Object doGetTransaction() throws TransactionException;

	/**
	 * 是否是一个已经存在的事务
	 *
	 * 检查给定的事务对象是否指向现有事务（即已经开始的事务）
	 * 将根据新事物的指定传播行为评估结果。
	 * 现有事务可能会被暂停（对于PROPAGATION_REQUIRES_NEW），或者新事务可能会参与现有事务（对于PROPAGATION_REQUIRED）。
	 * 默认实现返回false,假定通常不支持参与现有事务。当然，鼓励子类提供此类支持。
	 *
	 * Check if the given transaction object indicates an existing transaction
	 * (that is, a transaction which has already started).
	 * <p>The result will be evaluated according to the specified propagation
	 * behavior for the new transaction. An existing transaction might get
	 * suspended (in case of PROPAGATION_REQUIRES_NEW), or the new transaction
	 * might participate in the existing one (in case of PROPAGATION_REQUIRED).
	 * <p>The default implementation returns {@code false}, assuming that
	 * participating in existing transactions is generally not supported.
	 * Subclasses are of course encouraged to provide such support.
	 * @param transaction transaction object returned by doGetTransaction
	 * @return if there is an existing transaction
	 * @throws TransactionException in case of system errors
	 * @see #doGetTransaction
	 */
	protected boolean isExistingTransaction(Object transaction) throws TransactionException {
		return false;
	}

	/**
	 * 返回是否对嵌套事务使用保存点
	 * 默认值是true,这会导致委派给DefaultTransactionStatus创建和hold住一个保存点。
	 * 如果事务对象没有实现SavepointManager接口，将会抛出NestedTransactionNotSupportedException异常，
	 * 否则，SavepointManager将会创建一个新的保存点以划分嵌套事务的开始。
	 * 子类可以重写这个方法以返回false,从而可以在已经存在的事务的上下文中进一步调用doBegin方法，
	 * 这种情况下，doBegin方法需要做相应的处理。
	 * 例如，这适用于jta
	 * Return whether to use a savepoint for a nested transaction.
	 * <p>Default is {@code true}, which causes delegation to DefaultTransactionStatus
	 * for creating and holding a savepoint. If the transaction object does not implement
	 * the SavepointManager interface, a NestedTransactionNotSupportedException will be
	 * thrown. Else, the SavepointManager will be asked to create a new savepoint to
	 * demarcate the start of the nested transaction.
	 * <p>Subclasses can override this to return {@code false}, causing a further
	 * call to {@code doBegin} - within the context of an already existing transaction.
	 * The {@code doBegin} implementation needs to handle this accordingly in such
	 * a scenario. This is appropriate for JTA, for example.
	 * @see DefaultTransactionStatus#createAndHoldSavepoint
	 * @see DefaultTransactionStatus#rollbackToHeldSavepoint
	 * @see DefaultTransactionStatus#releaseHeldSavepoint
	 * @see #doBegin
	 */
	protected boolean useSavepointForNestedTransaction() {
		return true;
	}

	/**
	 * 根据给定的事务定义，开始一个新事务。不必关心应用传播行为，因为此抽象管理器已经处理了它。
	 *
	 * <p>当事务管理器决定实际开始新事务时，将调用此方法。之前没有任何事务，或者先前的事务已被暂停。
	 * <p>一种特殊情况是没有保存点的嵌套事务：
	 * 如果{@code useSavepointForNestedTransaction（）}返回“ false”，则在必要时将调用此方法以启动嵌套事务。
	 * 在这种情况下，将有一个活动事务：此方法的实现必须检测到此情况并启动适当的嵌套事务。
	 * Begin a new transaction with semantics according to the given transaction
	 * definition. Does not have to care about applying the propagation behavior,
	 * as this has already been handled by this abstract manager.
	 * <p>This method gets called when the transaction manager has decided to actually
	 * start a new transaction. Either there wasn't any transaction before, or the
	 * previous transaction has been suspended.
	 * <p>A special scenario is a nested transaction without savepoint: If
	 * {@code useSavepointForNestedTransaction()} returns "false", this method
	 * will be called to start a nested transaction when necessary. In such a context,
	 * there will be an active transaction: The implementation of this method has
	 * to detect this and start an appropriate nested transaction.
	 * 返回的事务对象
	 * @param transaction transaction object returned by {@code doGetTransaction}
	 * TransactionDefinition实例，描述传播属性，隔离级别，只读属性，超时，事务名称等信息
	 * @param definition a TransactionDefinition instance, describing propagation
	 * behavior, isolation level, read-only flag, timeout, and transaction name
	 * @throws TransactionException in case of creation or system errors
	 * @throws org.springframework.transaction.NestedTransactionNotSupportedException
	 * if the underlying transaction does not support nesting
	 */
	protected abstract void doBegin(Object transaction, TransactionDefinition definition)
			throws TransactionException;

	/**
	 * 挂起当前事务的资源
	 * 事务同步已经暂停
	 * 默认实现会抛出TransactionSuspensionNotSupportedException，假定通常不支持事务挂起
	 *
	 * Suspend the resources of the current transaction.
	 * Transaction synchronization will already have been suspended.
	 * <p>The default implementation throws a TransactionSuspensionNotSupportedException,
	 * assuming that transaction suspension is generally not supported.
	 * @param transaction transaction object returned by {@code doGetTransaction}
	 *
	 * 返回持有暂挂资源的对象（将其传递给doResume不会被检查）
	 * @return an object that holds suspended resources
	 * (will be kept unexamined for passing it into doResume)
	 * @throws org.springframework.transaction.TransactionSuspensionNotSupportedException
	 * if suspending is not supported by the transaction manager implementation
	 * @throws TransactionException in case of system errors
	 * @see #doResume
	 */
	protected Object doSuspend(Object transaction) throws TransactionException {
		throw new TransactionSuspensionNotSupportedException(
				"Transaction manager [" + getClass().getName() + "] does not support transaction suspension");
	}

	/**
	 * 恢复当前事务的资源，随后事务同步也会恢复
	 * Resume the resources of the current transaction.
	 * Transaction synchronization will be resumed afterwards.
	 *
	 * 假定通常不支持事务挂起，则默认实现将引发TransactionSuspensionNotSupportedException异常。
	 * <p>The default implementation throws a TransactionSuspensionNotSupportedException,
	  assuming that transaction suspension is generally not supported.
	 *
	 * {@code doGetTransaction}返回的事务对象
	 * @param transaction transaction object returned by {@code doGetTransaction}
	 * 持有暂挂资源的对象，由doSuspend返回
	 * @param suspendedResources the object that holds suspended resources,
	 * as returned by doSuspend
	 * @throws org.springframework.transaction.TransactionSuspensionNotSupportedException
	 * if resuming is not supported by the transaction manager implementation
	 * @throws TransactionException in case of system errors
	 * @see #doSuspend
	 */
	protected void doResume(@Nullable Object transaction, Object suspendedResources) throws TransactionException {
		throw new TransactionSuspensionNotSupportedException(
				"Transaction manager [" + getClass().getName() + "] does not support transaction suspension");
	}

	/**
	 * 全局事务标记为仅回滚时是否应该commit
	 *
	 * 返回是否以全局方式在已标记为仅回滚的事务上调用{@code doCommit}。
	 *
	 * <p>如果应用程序通过TransactionStatus在本地将事务设置为仅回滚，则不适用，而仅适用于事务本身被事务协调器标记为仅回滚的事务。
	 *
	 * <p>默认值为“ false”：本地事务策略通常不在事务本身中包含仅回滚标记，因此它们不能在事务提交过程中处理仅回滚事务。
	 * 因此，在这种情况下，AbstractPlatformTransactionManager将触发回滚，此后引发UnexpectedRollbackException。
	 *
	 * <p>如果具体的事务管理器期望{@code doCommit}调用（即使是仅回滚的事务），也可以重写此方法以返回“ true”，从而允许在那里进行特殊处理。
	 * 例如，对于JTA就是这种情况，其中{@code UserTransaction.commit}将检查只读标志本身，并引发相应的RollbackException，其中可能包括特定原因（例如事务超时）。
	 *
	 * <p>如果此方法返回“ true”，但是{@code doCommit}实现未引发异常，则此事务管理器本身将引发UnexpectedRollbackException。这不应该是典型的情况。
	 * 它主要用于检查行为异常的JTA提供程序，即使调用代码未请求回滚，它们也会以静默方式回滚。
	 *
	 * Return whether to call {@code doCommit} on a transaction that has been
	 * marked as rollback-only in a global fashion.
	 * <p>Does not apply if an application locally sets the transaction to rollback-only
	 * via the TransactionStatus, but only to the transaction itself being marked as
	 * rollback-only by the transaction coordinator.
	 * <p>Default is "false": Local transaction strategies usually don't hold the rollback-only
	 * marker in the transaction itself, therefore they can't handle rollback-only transactions
	 * as part of transaction commit. Hence, AbstractPlatformTransactionManager will trigger
	 * a rollback in that case, throwing an UnexpectedRollbackException afterwards.
	 * <p>Override this to return "true" if the concrete transaction manager expects a
	 * {@code doCommit} call even for a rollback-only transaction, allowing for
	 * special handling there. This will, for example, be the case for JTA, where
	 * {@code UserTransaction.commit} will check the read-only flag itself and
	 * throw a corresponding RollbackException, which might include the specific reason
	 * (such as a transaction timeout).
	 * <p>If this method returns "true" but the {@code doCommit} implementation does not
	 * throw an exception, this transaction manager will throw an UnexpectedRollbackException
	 * itself. This should not be the typical case; it is mainly checked to cover misbehaving
	 * JTA providers that silently roll back even when the rollback has not been requested
	 * by the calling code.
	 * @see #doCommit
	 * @see DefaultTransactionStatus#isGlobalRollbackOnly()
	 * @see DefaultTransactionStatus#isLocalRollbackOnly()
	 * @see org.springframework.transaction.TransactionStatus#setRollbackOnly()
	 * @see org.springframework.transaction.UnexpectedRollbackException
	 * @see javax.transaction.UserTransaction#commit()
	 * @see javax.transaction.RollbackException
	 */
	protected boolean shouldCommitOnGlobalRollbackOnly() {
		return false;
	}

	/**
	 * 准备进行提交，以在{@code beforeCommit}同步回调发生之前执行。
	 * <p>请注意，异常将传播到提交调用者，并导致事务回滚。
	 *
	 * Make preparations for commit, to be performed before the
	 * {@code beforeCommit} synchronization callbacks occur.
	 * <p>Note that exceptions will get propagated to the commit caller
	 * and cause a rollback of the transaction.
	 * @param status the status representation of the transaction
	 * @throws RuntimeException in case of errors; will be <b>propagated to the caller</b>
	 * (note: do not throw TransactionException subclasses here!)
	 */
	protected void prepareForCommit(DefaultTransactionStatus status) {
	}

	/**
	 * Perform an actual commit of the given transaction.
	 * <p>An implementation does not need to check the "new transaction" flag
	 * or the rollback-only flag; this will already have been handled before.
	 * Usually, a straight commit will be performed on the transaction object
	 * contained in the passed-in status.
	 * @param status the status representation of the transaction
	 * @throws TransactionException in case of commit or system errors
	 * @see DefaultTransactionStatus#getTransaction
	 */
	protected abstract void doCommit(DefaultTransactionStatus status) throws TransactionException;

	/**
	 * Perform an actual rollback of the given transaction.
	 * <p>An implementation does not need to check the "new transaction" flag;
	 * this will already have been handled before. Usually, a straight rollback
	 * will be performed on the transaction object contained in the passed-in status.
	 * @param status the status representation of the transaction
	 * @throws TransactionException in case of system errors
	 * @see DefaultTransactionStatus#getTransaction
	 */
	protected abstract void doRollback(DefaultTransactionStatus status) throws TransactionException;

	/**
	 * Set the given transaction rollback-only. Only called on rollback
	 * if the current transaction participates in an existing one.
	 * <p>The default implementation throws an IllegalTransactionStateException,
	 * assuming that participating in existing transactions is generally not
	 * supported. Subclasses are of course encouraged to provide such support.
	 * @param status the status representation of the transaction
	 * @throws TransactionException in case of system errors
	 */
	protected void doSetRollbackOnly(DefaultTransactionStatus status) throws TransactionException {
		throw new IllegalTransactionStateException(
				"Participating in existing transactions is not supported - when 'isExistingTransaction' " +
				"returns true, appropriate 'doSetRollbackOnly' behavior must be provided");
	}

	/**
	 * Register the given list of transaction synchronizations with the existing transaction.
	 * <p>Invoked when the control of the Spring transaction manager and thus all Spring
	 * transaction synchronizations end, without the transaction being completed yet. This
	 * is for example the case when participating in an existing JTA or EJB CMT transaction.
	 * <p>The default implementation simply invokes the {@code afterCompletion} methods
	 * immediately, passing in "STATUS_UNKNOWN". This is the best we can do if there's no
	 * chance to determine the actual outcome of the outer transaction.
	 * @param transaction transaction object returned by {@code doGetTransaction}
	 * @param synchronizations a List of TransactionSynchronization objects
	 * @throws TransactionException in case of system errors
	 * @see #invokeAfterCompletion(java.util.List, int)
	 * @see TransactionSynchronization#afterCompletion(int)
	 * @see TransactionSynchronization#STATUS_UNKNOWN
	 */
	protected void registerAfterCompletionWithExistingTransaction(
			Object transaction, List<TransactionSynchronization> synchronizations) throws TransactionException {

		logger.debug("Cannot register Spring after-completion synchronization with existing transaction - " +
				"processing Spring after-completion callbacks immediately, with outcome status 'unknown'");
		invokeAfterCompletion(synchronizations, TransactionSynchronization.STATUS_UNKNOWN);
	}

	/**
	 * Cleanup resources after transaction completion.
	 * <p>Called after {@code doCommit} and {@code doRollback} execution,
	 * on any outcome. The default implementation does nothing.
	 * <p>Should not throw any exceptions but just issue warnings on errors.
	 * @param transaction transaction object returned by {@code doGetTransaction}
	 */
	protected void doCleanupAfterCompletion(Object transaction) {
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		// Rely on default serialization; just initialize state after deserialization.
		ois.defaultReadObject();

		// Initialize transient fields.
		this.logger = LogFactory.getLog(getClass());
	}


	/**
	 * 暂挂资源的持有者
	 * Holder for suspended resources.
	 * Used internally by {@code suspend} and {@code resume}.
	 */
	protected static final class SuspendedResourcesHolder {

		/**暂挂的资源,比如ResouceHolder，连接句柄*/
		@Nullable
		private final Object suspendedResources;

		/**暂挂的事务同步器*/
		@Nullable
		private List<TransactionSynchronization> suspendedSynchronizations;

		@Nullable
		private String name;

		/**只读属性*/
		private boolean readOnly;

		/**隔离级别*/
		@Nullable
		private Integer isolationLevel;

		/**是否激活*/
		private boolean wasActive;

		private SuspendedResourcesHolder(Object suspendedResources) {
			this.suspendedResources = suspendedResources;
		}

		private SuspendedResourcesHolder(
				@Nullable Object suspendedResources, List<TransactionSynchronization> suspendedSynchronizations,
				@Nullable String name, boolean readOnly, @Nullable Integer isolationLevel, boolean wasActive) {

			this.suspendedResources = suspendedResources;
			this.suspendedSynchronizations = suspendedSynchronizations;
			this.name = name;
			this.readOnly = readOnly;
			this.isolationLevel = isolationLevel;
			this.wasActive = wasActive;
		}
	}

}
