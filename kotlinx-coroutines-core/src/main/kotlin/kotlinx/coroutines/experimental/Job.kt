/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental

import kotlinx.coroutines.experimental.internal.LockFreeLinkedListHead
import kotlinx.coroutines.experimental.internal.LockFreeLinkedListNode
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.coroutines.experimental.AbstractCoroutineContextElement
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.CoroutineContext

// --------------- core job interfaces ---------------

/**
 * A background job.
 * A job can be _cancelled_ at any time with [cancel] function that forces it to become _completed_ immediately.
 *
 * It has two states:
 * * _Active_ (initial state) -- [isActive] `true`,
 *   [getCompletionException] throws [IllegalStateException].
 * * _Completed_ (final state) -- [isActive] `false`.
 *
 * A job in the coroutine [context][CoroutineScope.context] represents the coroutine itself.
 * A job is active while the coroutine is working and job's cancellation aborts the coroutine when
 * the coroutine is suspended on a _cancellable_ suspension point by throwing [CancellationException]
 * or the cancellation cause inside the coroutine.
 *
 * A job can have a _parent_. A job with a parent is cancelled when its parent completes.
 *
 * All functions on this interface and on all interfaces derived from it are **thread-safe** and can
 * be safely invoked from concurrent coroutines without external synchronization.
 */
public interface Job : CoroutineContext.Element {
    /**
     * Key for [Job] instance in the coroutine context.
     */
    public companion object Key : CoroutineContext.Key<Job> {
        /**
         * Creates new job object. It is optionally a child of a [parent] job.
         */
        public operator fun invoke(parent: Job? = null): Job = JobImpl(parent)
    }

    /**
     * Returns `true` when job is still active.
     */
    public val isActive: Boolean

    /**
     * Returns the exception that signals the completion of this job -- it returns the original
     * [cancel] cause or an instance of [CancellationException] if this job had completed
     * normally or was cancelled without a cause. This function throws
     * [IllegalStateException] when invoked for an [active][isActive] job.
     *
     * The [cancellable][suspendCancellableCoroutine] suspending functions throw this exception
     * when trying to suspend in the context of this job.
     */
    fun getCompletionException(): Throwable

    /**
     * Registers completion handler. The action depends on the state of this job.
     * When job is cancelled with [cancel], then the handler is immediately invoked
     * with a cancellation cause or with a fresh [CancellationException].
     * Otherwise, handler will be invoked once when this job is complete
     * (cancellation also is a form of completion).
     *
     * The resulting [Registration] can be used to [Registration.unregister] if this
     * registration is no longer needed. There is no need to unregister after completion.
     */
    public fun onCompletion(handler: CompletionHandler): Registration

    /**
     * Cancel this activity with an optional cancellation [cause]. The result is `true` if this job was
     * cancelled as a result of this invocation and `false` otherwise
     * (if it was already _completed_ or if it is [NonCancellable]).
     * Repeated invocations of this function have no effect and always produce `false`.
     *
     * When cancellation has a clear reason in the code, an instance of [CancellationException] should be created
     * at the corresponding original cancellation site and passed into this method to aid in debugging by providing
     * both the context of cancellation and text description of the reason.
     */
    public fun cancel(cause: Throwable? = null): Boolean

    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated(message = "Operator '+' on two Job objects is meaningless. " +
                    "Job is a coroutine context element and `+` is a set-sum operator for coroutine contexts. " +
                    "The job to the right of `+` just replaces the job the left of `+`.",
            level = DeprecationLevel.ERROR)
    public operator fun plus(other: Job) = other

    /**
     * Registration object for [onCompletion]. It can be used to [unregister] if needed.
     * There is no need to unregister after completion.
     */
    public interface Registration {
        /**
         * Unregisters completion handler.
         */
        public fun unregister()
    }
}

public typealias CompletionHandler = (Throwable?) -> Unit

/**
 * Thrown by cancellable suspending functions if the [Job] of the coroutine is cancelled while it is suspending.
 */
public typealias CancellationException = java.util.concurrent.CancellationException

/**
 * Unregisters a specified [registration] when this job is complete.
 * This is a shortcut for the following code with slightly more efficient implementation (one fewer object created).
 * ```
 * onCompletion { registration.unregister() }
 * ```
 */
public fun Job.unregisterOnCompletion(registration: Job.Registration): Job.Registration =
    onCompletion(UnregisterOnCompletion(this, registration))

/**
 * Cancels a specified [future] when this job is complete.
 * This is a shortcut for the following code with slightly more efficient implementation (one fewer object created).
 * ```
 * onCompletion { future.cancel(true) }
 * ```
 */
public fun Job.cancelFutureOnCompletion(future: Future<*>): Job.Registration =
    onCompletion(CancelFutureOnCompletion(this, future))

internal fun Job.removeOnCompletion(node: LockFreeLinkedListNode): Job.Registration =
    onCompletion(RemoveOnCompletion(this, node))

/**
 * Suspends coroutine until this job is complete. This invocation resumes normally (without exception)
 * when the job is complete for any reason.
 *
 * This suspending function is cancellable. If the [Job] of the invoking coroutine is completed while this
 * suspending function is suspended, this function immediately resumes with [CancellationException].
 */
public suspend fun Job.join() {
    if (!isActive) return // fast path
    return suspendCancellableCoroutine { cont ->
        cont.unregisterOnCompletion(onCompletion(ResumeOnCompletion(this, cont)))
    }
}

// --------------- utility classes to simplify job implementation

/**
 * A concrete implementation of [Job]. It is optionally a child to a parent job.
 * This job is cancelled when the parent is complete, but not vise-versa.
 *
 * This is an open class designed for extension by more specific classes that might augment the
 * state and mare store addition state information for completed jobs, like their result values.
 */
internal open class JobSupport : AbstractCoroutineContextElement(Job), Job {
    /*
       === States ===
       name       state class    is Active?
       ------     ------------   ---------
       EMPTY      Empty        : Active  -- no completion listener
       SINGLE     JobNode      : Active  -- a single completion listener
       SINGLE+    JobNode      : Active  -- a single completion listener + NodeList added as its next
       LIST       NodeList     : Active  -- a list of listeners (promoted just once, does not got back to JobNode/Empty)
       FINAL_C    Cancelled    : !Active -- cancelled (final state)
       FINAL_F    Failed       : !Active -- failed for other reason (final state)
       FINAL_R    <any>        : !Active -- produced some result
       
       === Transitions ===
       
                  Active states             !Active states
                   +---------+               +----------+
      initial -+-> |  EMPTY  | ------------> |  FINAL_* |
               |   +---------+               +----------+
               |     |     ^                       ^
               |     V     |                       |
               |   +---------+                     |
               |   | SINGLE  | --------------------+
               |   +---------+                     |
               |        |                          |
               |        V                          |
               |   +---------+                     |
               +-- | SINGLE+ | --------------------+
                   +---------+                     |
                        |                          |
                        V                          |
                   +---------+                     |
                   |  LIST   | --------------------+
                   +---------+
     */

    @Volatile
    private var state: Any? = Empty // shared object while we have no listeners

    @Volatile
    private var registration: Job.Registration? = null

    protected companion object {
        @JvmStatic
        private val STATE: AtomicReferenceFieldUpdater<JobSupport, Any?> =
            AtomicReferenceFieldUpdater.newUpdater(JobSupport::class.java, Any::class.java, "state")
    }

    /**
     * Initializes parent job.
     * It shall be invoked at most once after construction after all other initialization.
     */
    fun initParentJob(parent: Job?) {
        check(registration == null)
        if (parent == null) {
            registration = EmptyRegistration
            return
        }
        // directly pass HandlerNode to parent scope to optimize one closure object (see makeNode)
        val newRegistration = parent.onCompletion(CancelOnCompletion(parent, this))
        registration = newRegistration
        // now check our state _after_ registering (see updateState order of actions)
        if (state !is Active) newRegistration.unregister()
    }

    /**
     * Returns current state of this job.
     */
    fun getState(): Any? = state

    /**
     * Tries to update current [state][getState] of this job.
     */
    fun updateState(expect: Any, update: Any?): Boolean {
        if (!tryUpdateState(expect, update)) return false
        completeUpdateState(expect, update)
        return true
    }

    fun tryUpdateState(expect: Any, update: Any?): Boolean  {
        require(expect is Active && update !is Active) // only active -> inactive transition is allowed
        if (!STATE.compareAndSet(this, expect, update)) return false
        // #1. Update linked state before invoking completion handlers
        onStateUpdate(update)
        // #2. Unregister from parent job
        registration?.unregister() // volatile read registration _after_ state was updated
        return true // continues in completeUpdateState
    }

    fun completeUpdateState(expect: Any, update: Any?) {
        // #3. Invoke completion handlers
        val cause = (update as? CompletedExceptionally)?.exception
        var completionException: Throwable? = null
        when (expect) {
            // SINGLE/SINGLE+ state -- one completion handler (common case)
            is JobNode -> try {
                expect.invoke(cause)
            } catch (ex: Throwable) {
                completionException = ex
            }
            // LIST state -- a list of completion handlers
            is NodeList -> expect.forEach<JobNode> { node ->
                try {
                    node.invoke(cause)
                } catch (ex: Throwable) {
                    completionException?.apply { addSuppressed(ex) } ?: run { completionException = ex }
                }

            }
            // otherwise -- do nothing (Empty)
            else -> check(expect === Empty)
        }
        // #4. Do other (overridable) processing after completion handlers
        completionException?.let { handleCompletionException(it) }
        afterCompletion(update)
    }

    final override val isActive: Boolean get() = state is Active

    override fun getCompletionException(): Throwable {
        val state = getState()
        return when (state) {
            is Active -> throw IllegalStateException("Job is still active")
            is CompletedExceptionally -> state.exception
            else -> CancellationException("Job has completed normally")
        }
    }

    final override fun onCompletion(handler: CompletionHandler): Job.Registration {
        var nodeCache: JobNode? = null
        while (true) { // lock-free loop on state
            val state = this.state
            when {
                // EMPTY state -- no completion handlers
                state === Empty -> {
                    // try move to SINGLE state
                    val node = nodeCache ?: makeNode(handler).also { nodeCache = it }
                    if (STATE.compareAndSet(this, state, node)) return node
                }
                // SINGLE/SINGLE+ state -- one completion handler
                state is JobNode -> {
                    // try promote it to the list (SINGLE+ state)
                    state.addFirstIfEmpty(NodeList())
                    // it must be in SINGLE+ state or state has changed (node could have need removed from state)
                    val list = state.next() // either NodeList or somebody else won the race, updated state
                    // just attempt converting it to list if state is still the same, then continue lock-free loop
                    STATE.compareAndSet(this, state, list)
                }
                // LIST -- a list of completion handlers
                state is NodeList -> {
                    val node = nodeCache ?: makeNode(handler).also { nodeCache = it }
                    if (state.addLastIf(node) { this.state === state }) return node
                }
                // is not active anymore
                else -> {
                    handler((state as? CompletedExceptionally)?.exception)
                    return EmptyRegistration
                }
            }
        }
    }

    fun removeNode(node: JobNode) {
        // remove logic depends on the state of the job
        while (true) { // lock-free loop on job state
            val state = this.state
            when {
                // EMPTY state -- no completion handlers
                state === Empty -> return
                // SINGE/SINGLE+ state -- one completion handler
                state is JobNode -> {
                    if (state !== this) return // a different job node --> we were already removed
                    // try remove and revert back to empty state
                    if (STATE.compareAndSet(this, state, Empty)) return
                }
                // LIST -- a list of completion handlers
                state is NodeList -> {
                    // remove node from the list
                    node.remove()
                    return
                }
                // is not active anymore
                else -> return
            }
        }
    }

    final override fun cancel(cause: Throwable?): Boolean {
        while (true) { // lock-free loop on state
            val state = this.state as? Active ?: return false // quit if not active anymore
            if (updateState(state, Cancelled(cause))) return true
        }
    }

    /**
     * Override to make linked state changes before completion handlers are invoked.
     */
    open fun onStateUpdate(update: Any?) {}

    /**
     * Override to process any exceptions that were encountered while invoking [onCompletion] handlers.
     */
    open fun handleCompletionException(closeException: Throwable) {
        throw closeException
    }

    /**
     * Override for post-completion actions that need to do something with the state.
     */
    open fun afterCompletion(state: Any?) {}

    private fun makeNode(handler: CompletionHandler): JobNode =
            (handler as? JobNode)?.also { require(it.job === this) }
                    ?: InvokeOnCompletion(this, handler)

    // for nicer debugging
    override fun toString(): String = "${javaClass.simpleName}{isActive=$isActive}"

    /**
     * Marker interface for active [state][getState] of a job.
     */
    internal interface Active

    private object Empty : Active {
        override fun toString(): String = "Empty"
    }

    private class NodeList : LockFreeLinkedListHead(), Active {
        override fun toString(): String = buildString {
            append("[")
            var first = true
            this@NodeList.forEach<JobNode> { node ->
                if (first) first = false else append(", ")
                append(node)
            }
            append("]")
        }
    }

    /**
     * Class for a [state][getState] of a job that had completed exceptionally, including cancellation.
     */
    internal open class CompletedExceptionally(cause: Throwable?) {
        @Volatile
        private var _exception: Throwable? = cause // materialize CancellationException on first need

        val exception: Throwable get() =
            _exception ?: // atomic read volatile var or else create new
                CancellationException("Job was cancelled").also { _exception = it }

        override fun toString(): String = "${javaClass.simpleName}[$exception]"
    }

    /**
     * A specific subclass of [CompletedExceptionally] for cancelled jobs.
     */
    internal class Cancelled(cause: Throwable?) : CompletedExceptionally(cause)
}

internal abstract class JobNode(
    val job: Job
) : LockFreeLinkedListNode(), Job.Registration, CompletionHandler, JobSupport.Active {
    // if unregister is called on this instance, then Job was an instance of JobSupport that added this node it itself
    // directly without wrapping
    final override fun unregister() = (job as JobSupport).removeNode(this)
    override abstract fun invoke(reason: Throwable?)
}

private class InvokeOnCompletion(
    job: Job,
    val handler: CompletionHandler
) : JobNode(job)  {
    override fun invoke(reason: Throwable?) = handler.invoke(reason)
    override fun toString() = "InvokeOnCompletion[${handler.javaClass.name}@${Integer.toHexString(System.identityHashCode(handler))}]"
}

private class ResumeOnCompletion(
    job: Job,
    val continuation: Continuation<Unit>
) : JobNode(job)  {
    override fun invoke(reason: Throwable?) = continuation.resume(Unit)
    override fun toString() = "ResumeOnCompletion[$continuation]"
}

private class UnregisterOnCompletion(
    job: Job,
    val registration: Job.Registration
) : JobNode(job) {
    override fun invoke(reason: Throwable?) = registration.unregister()
    override fun toString(): String = "UnregisterOnCompletion[$registration]"
}

private class CancelOnCompletion(
    parentJob: Job,
    val subordinateJob: Job
) : JobNode(parentJob) {
    override fun invoke(reason: Throwable?) { subordinateJob.cancel(reason) }
    override fun toString(): String = "CancelOnCompletion[$subordinateJob]"
}

internal object EmptyRegistration : Job.Registration {
    override fun unregister() {}
    override fun toString(): String = "EmptyRegistration"
}

private class CancelFutureOnCompletion(
    job: Job,
    val future: Future<*>
) : JobNode(job)  {
    override fun invoke(reason: Throwable?) {
        // Don't interrupt when cancelling future on completion, because no one is going to reset this
        // interruption flag and it will cause spurious failures elsewhere
        future.cancel(false)
    }
    override fun toString() = "CancelFutureOnCompletion[$future]"
}

private class RemoveOnCompletion(
    job: Job,
    val node: LockFreeLinkedListNode
) : JobNode(job)  {
    override fun invoke(reason: Throwable?) { node.remove() }
    override fun toString() = "RemoveOnCompletion[$node]"
}

private class JobImpl(parent: Job? = null) : JobSupport() {
    init { initParentJob(parent) }
}