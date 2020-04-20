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

package org.springframework.transaction.interceptor;

import java.util.Properties;

import org.springframework.aop.Pointcut;
import org.springframework.aop.framework.AbstractSingletonProxyFactoryBean;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 代理工厂bean，用于简化声明式事务处理。
 * 这是使用单独的{@link TransactionInterceptor}定义的标准AOP{@link org.springframework.aop.framework.ProxyFactoryBean}的便捷替代方法。
 * Proxy factory bean for simplified declarative transaction handling.
 * This is a convenient alternative to a standard AOP
 * {@link org.springframework.aop.framework.ProxyFactoryBean}
 * with a separate {@link TransactionInterceptor} definition.
 *
 * 历史性注解：
 * 此类最初旨在涵盖声明式事务划分的典型情况：
 * 即，将单个对象包装为事务代理，代理目标实现的所有接口。
 * 但是，在Spring 2.0及更高版本中，此处提供的功能已被更方便的{@code tx：} XML名称空间所取代。
 * 请参阅Spring参考文档的<a href="https://bit.ly/qUwvwz">声明式事务管理</a>部分，以了解用于管理Spring应用程序中事务的更现代的选项。
 *
 * 由于这些原因，用户应该偏爱：
 * {@code tx：} XML名称空间以及
 * @{@ link org.springframework.transaction.annotation.Transactional Transactional}
 * @{@ @{@link org.springframework.transaction.annotation.EnableTransactionManagement EnableTransactionManagement}
 * 等注解。
 *
 * <p><strong>HISTORICAL NOTE:</strong> This class was originally designed to cover the
 * typical case of declarative transaction demarcation: namely, wrapping a singleton
 * target object with a transactional proxy, proxying all the interfaces that the target
 * implements. However, in Spring versions 2.0 and beyond, the functionality provided here
 * is superseded by the more convenient {@code tx:} XML namespace. See the <a
 * href="https://bit.ly/qUwvwz">declarative transaction management</a> section of the
 * Spring reference documentation to understand the modern options for managing
 * transactions in Spring applications. For these reasons, <strong>users should favor of
 * the {@code tx:} XML namespace as well as
 * the @{@link org.springframework.transaction.annotation.Transactional Transactional}
 * and @{@link org.springframework.transaction.annotation.EnableTransactionManagement
 * EnableTransactionManagement} annotations.</strong>
 *
 * 需要指定三个主要属性：
 * transactionManager：{@link PlatformTransactionManager}的实现类，也就是具体的事务管理器
 * target：应为其创建事务代理的目标对象
 * transactionAttributes：每个目标方法的事务相关属性，比如传播行为，只读标志，
 * <p>There are three main properties that need to be specified:
 * <ul>
 * <li>"transactionManager": the {@link PlatformTransactionManager} implementation to use
 * (for example, a {@link org.springframework.transaction.jta.JtaTransactionManager} instance)
 * <li>"target": the target object that a transactional proxy should be created for
 * <li>"transactionAttributes": the transaction attributes (for example, propagation
 * behavior and "readOnly" flag) per target method name (or method name pattern)
 * </ul>
 *
 * <p>If the "transactionManager" property is not set explicitly and this {@link FactoryBean}
 * is running in a {@link ListableBeanFactory}, a single matching bean of type
 * {@link PlatformTransactionManager} will be fetched from the {@link BeanFactory}.
 *
 * 与{@link TransactionInterceptor}相比，事务属性被指定为属性：以方法名称为键，事务属性描述符为值。方法名称始终应用于目标类。
 * <p>In contrast to {@link TransactionInterceptor}, the transaction attributes are
 * specified as properties, with method names as keys and transaction attribute
 * descriptors as values. Method names are always applied to the target class.
 *
 * 在内部，使用{@link TransactionInterceptor}实例，但是此类的用户不必关心。
 * （可选）可以指定方法切入点以引起基础{@link TransactionInterceptor}的条件调用。
 *
 * <p>Internally, a {@link TransactionInterceptor} instance is used, but the user of this
 * class does not have to care. Optionally, a method pointcut can be specified
 * to cause conditional invocation of the underlying {@link TransactionInterceptor}.
 *
 * 可以设置“ preInterceptors”和“ postInterceptors”属性，以向混合中添加额外的拦截器
 * 例如 {@link org.springframework.aop.interceptor.PerformanceMonitorInterceptor}.
 *
 * <p>The "preInterceptors" and "postInterceptors" properties can be set to add
 * additional interceptors to the mix, like
 * {@link org.springframework.aop.interceptor.PerformanceMonitorInterceptor}.
 *
 * 此类通常与父/子bean定义一起使用。
 * 通常，您将在抽象父bean定义中定义事务管理器和默认事务属性（用于方法名称模式），
 * 从而为特定目标对象派生具体的子bean定义。这将每个bean的定义工作减少到最低限度。
 * <p><b>HINT:</b> This class is often used with parent / child bean definitions.
 * Typically, you will define the transaction manager and default transaction
 * attributes (for method name patterns) in an abstract parent bean definition,
 * deriving concrete child bean definitions for specific target objects.
 * This reduces the per-bean definition effort to a minimum.
 *
 * <pre code="class">
 * &lt;bean id="baseTransactionProxy" class="org.springframework.transaction.interceptor.TransactionProxyFactoryBean"
 *     abstract="true"&gt;
 *   &lt;property name="transactionManager" ref="transactionManager"/&gt;
 *   &lt;property name="transactionAttributes"&gt;
 *     &lt;props&gt;
 *       &lt;prop key="insert*"&gt;PROPAGATION_REQUIRED&lt;/prop&gt;
 *       &lt;prop key="update*"&gt;PROPAGATION_REQUIRED&lt;/prop&gt;
 *       &lt;prop key="*"&gt;PROPAGATION_REQUIRED,readOnly&lt;/prop&gt;
 *     &lt;/props&gt;
 *   &lt;/property&gt;
 * &lt;/bean&gt;
 *
 * &lt;bean id="myProxy" parent="baseTransactionProxy"&gt;
 *   &lt;property name="target" ref="myTarget"/&gt;
 * &lt;/bean&gt;
 *
 * &lt;bean id="yourProxy" parent="baseTransactionProxy"&gt;
 *   &lt;property name="target" ref="yourTarget"/&gt;
 * &lt;/bean&gt;</pre>
 *
 * @author Juergen Hoeller
 * @author Dmitriy Kopylenko
 * @author Rod Johnson
 * @author Chris Beams
 * @since 21.08.2003
 * @see #setTransactionManager
 * @see #setTarget
 * @see #setTransactionAttributes
 * @see TransactionInterceptor
 * @see org.springframework.aop.framework.ProxyFactoryBean
 */
@SuppressWarnings("serial")
public class TransactionProxyFactoryBean extends AbstractSingletonProxyFactoryBean
		implements BeanFactoryAware {

	/**事务拦截器,通过这个事务拦截器，Spring封装了事务处理实现*/
	private final TransactionInterceptor transactionInterceptor = new TransactionInterceptor();

	@Nullable
	private Pointcut pointcut;


	/**
	 * 设置默认的事务管理器。
	 *
	 * 这将执行实际的事务管理：此类仅是调用它的一种方式。
	 * Set the default transaction manager. This will perform actual
	 * transaction management: This class is just a way of invoking it.
	 * @see TransactionInterceptor#setTransactionManager
	 */
	public void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionInterceptor.setTransactionManager(transactionManager);
	}

	/**
	 * 设置属性
	 * 方法名称为键，事务属性描述符（通过TransactionAttributeEditor解析）为值：
	 * 例如key=“ myMethod”，value=“ PROPAGATION_REQUIRED，readOnly”。
	 * 注意：方法名称始终应用于目标类，无论是在接口中定义还是在类本身中定义。
	 * 内部，将根据给定的属性创建一个NameMatchTransactionAttributeSource。
	 *
	 * 以Properties的形式封装通过依赖注入的事务属性
	 *
	 * Set properties with method names as keys and transaction attribute
	 * descriptors (parsed via TransactionAttributeEditor) as values:
	 * e.g. key = "myMethod", value = "PROPAGATION_REQUIRED,readOnly".
	 * <p>Note: Method names are always applied to the target class,
	 * no matter if defined in an interface or the class itself.
	 * <p>Internally, a NameMatchTransactionAttributeSource will be
	 * created from the given properties.
	 * @see #setTransactionAttributeSource
	 * @see TransactionInterceptor#setTransactionAttributes
	 * @see TransactionAttributeEditor
	 * @see NameMatchTransactionAttributeSource
	 */
	public void setTransactionAttributes(Properties transactionAttributes) {
		this.transactionInterceptor.setTransactionAttributes(transactionAttributes);
	}

	/**
	 * Set the transaction attribute source which is used to find transaction
	 * attributes. If specifying a String property value, a PropertyEditor
	 * will create a MethodMapTransactionAttributeSource from the value.
	 * @see #setTransactionAttributes
	 * @see TransactionInterceptor#setTransactionAttributeSource
	 * @see TransactionAttributeSourceEditor
	 * @see MethodMapTransactionAttributeSource
	 * @see NameMatchTransactionAttributeSource
	 * @see org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
	 */
	public void setTransactionAttributeSource(TransactionAttributeSource transactionAttributeSource) {
		this.transactionInterceptor.setTransactionAttributeSource(transactionAttributeSource);
	}

	/**
	 * 设置切入点
	 * 即可以根据传递的方法和属性而导致有条件地调用TransactionInterceptor的bean。
	 * 始终会调用其他拦截器。
	 *
	 * Set a pointcut, i.e a bean that can cause conditional invocation
	 * of the TransactionInterceptor depending on method and attributes passed.
	 * Note: Additional interceptors are always invoked.
	 * @see #setPreInterceptors
	 * @see #setPostInterceptors
	 */
	public void setPointcut(Pointcut pointcut) {
		this.pointcut = pointcut;
	}

	/**
	 * This callback is optional: If running in a BeanFactory and no transaction
	 * manager has been set explicitly, a single matching bean of type
	 * {@link PlatformTransactionManager} will be fetched from the BeanFactory.
	 * @see org.springframework.beans.factory.BeanFactory#getBean(Class)
	 * @see org.springframework.transaction.PlatformTransactionManager
	 */
	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.transactionInterceptor.setBeanFactory(beanFactory);
	}


	/**
	 * 为此FactoryBean的TransactionInterceptor创建一个通知器
	 * Creates an advisor for this FactoryBean's TransactionInterceptor.
	 */
	@Override
	protected Object createMainInterceptor() {
		this.transactionInterceptor.afterPropertiesSet();
		if (this.pointcut != null) {
			//如果pointcut不为null,使用默认的通知器DefaultPointcutAdvisor，并配置事务拦截器
			return new DefaultPointcutAdvisor(this.pointcut, this.transactionInterceptor);
		}
		else {
			//如果没有配置pointcut,使用TransactionAttributeSourceAdvisor作为通知器，并设置事务拦截器
			// Rely on default pointcut.
			return new TransactionAttributeSourceAdvisor(this.transactionInterceptor);
		}
	}

	/**
	 * 从4.2开始，此方法将{@link TransactionalProxy}添加到代理接口集，以避免重新处理事务元数据。
	 *
	 * As of 4.2, this method adds {@link TransactionalProxy} to the set of
	 * proxy interfaces in order to avoid re-processing of transaction metadata.
	 */
	@Override
	protected void postProcessProxyFactory(ProxyFactory proxyFactory) {
		proxyFactory.addInterface(TransactionalProxy.class);
	}

}
