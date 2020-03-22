/*
 * Copyright 2002-2012 the original author or authors.
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

package org.springframework.core;

/**
 * 由透明资源代理实现的接口，需要将其视为与底层资源相等，例如用于一致的查找关键字比较。
 * 请注意，此接口确实暗示了这种特殊的语义，并且不构成通用的mixin！
 * Interface to be implemented by transparent resource proxies that need to be
 * considered as equal to the underlying resource, for example for consistent
 * lookup key comparisons. Note that this interface does imply such special
 * semantics and does not constitute a general-purpose mixin!
 *
 * 在TransactionSynchronizationManager中这样的包装器将自动展开，以便进行key的比较
 * <p>Such wrappers will automatically be unwrapped for key comparisons in
 * {@link org.springframework.transaction.support.TransactionSynchronizationManager}.
 *
 * 仅完全透明的代理，例如对于重定向或服务查找，应该实现此接口。
 * 用新行为装饰目标对象的代理（例如AOP代理）
 * <p>Only fully transparent proxies, e.g. for redirection or service lookups,
 * are supposed to implement this interface. Proxies that decorate the target
 * object with new behavior, such as AOP proxies, do <i>not</i> qualify here!
 *
 * @author Juergen Hoeller
 * @since 2.5.4
 * @see org.springframework.transaction.support.TransactionSynchronizationManager
 */
public interface InfrastructureProxy {

	/**
	 * 返回底层资源
	 * Return the underlying resource (never {@code null}).
	 */
	Object getWrappedObject();

}
