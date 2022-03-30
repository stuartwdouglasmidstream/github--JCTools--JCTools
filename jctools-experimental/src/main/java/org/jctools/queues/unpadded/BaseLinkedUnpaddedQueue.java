/*
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
package org.jctools.queues.unpadded;

import org.jctools.queues.LinkedQueueNode;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MessagePassingQueueUtil;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.Queue;

import static org.jctools.util.UnsafeAccess.UNSAFE;
import static org.jctools.util.UnsafeAccess.fieldOffset;

abstract class BaseLinkedUnpaddedQueuePad0<E> extends AbstractQueue<E> implements MessagePassingQueue<E>
{

    //    * drop 8b as object header acts as padding and is >= 8b *
}

// $gen:ordered-fields
abstract class BaseLinkedUnpaddedQueueProducerNodeRef<E> extends BaseLinkedUnpaddedQueuePad0<E>
{
    final static long P_NODE_OFFSET = fieldOffset(BaseLinkedUnpaddedQueueProducerNodeRef.class, "producerNode");

    private volatile LinkedQueueNode<E> producerNode;

    final void spProducerNode(LinkedQueueNode<E> newValue)
    {
        UNSAFE.putObject(this, P_NODE_OFFSET, newValue);
    }

    final void soProducerNode(LinkedQueueNode<E> newValue)
    {
        UNSAFE.putOrderedObject(this, P_NODE_OFFSET, newValue);
    }

    final LinkedQueueNode<E> lvProducerNode()
    {
        return producerNode;
    }

    final boolean casProducerNode(LinkedQueueNode<E> expect, LinkedQueueNode<E> newValue)
    {
        return UNSAFE.compareAndSwapObject(this, P_NODE_OFFSET, expect, newValue);
    }

    final LinkedQueueNode<E> lpProducerNode()
    {
        return producerNode;
    }
}

abstract class BaseLinkedUnpaddedQueuePad1<E> extends BaseLinkedUnpaddedQueueProducerNodeRef<E>
{

}

//$gen:ordered-fields
abstract class BaseLinkedUnpaddedQueueConsumerNodeRef<E> extends BaseLinkedUnpaddedQueuePad1<E>
{
    private final static long C_NODE_OFFSET = fieldOffset(BaseLinkedUnpaddedQueueConsumerNodeRef.class,"consumerNode");

    private LinkedQueueNode<E> consumerNode;

    final void spConsumerNode(LinkedQueueNode<E> newValue)
    {
        consumerNode = newValue;
    }

    @SuppressWarnings("unchecked")
    final LinkedQueueNode<E> lvConsumerNode()
    {
        return (LinkedQueueNode<E>) UNSAFE.getObjectVolatile(this, C_NODE_OFFSET);
    }

    final LinkedQueueNode<E> lpConsumerNode()
    {
        return consumerNode;
    }
}

abstract class BaseLinkedUnpaddedQueuePad2<E> extends BaseLinkedUnpaddedQueueConsumerNodeRef<E>
{

}

/**
 * A base data structure for concurrent linked queues. For convenience also pulled in common single consumer
 * methods since at this time there's no plan to implement MC.
 */
abstract class BaseLinkedUnpaddedQueue<E> extends BaseLinkedUnpaddedQueuePad2<E>
{

    @Override
    public final Iterator<E> iterator()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString()
    {
        return this.getClass().getName();
    }

    protected final LinkedQueueNode<E> newNode()
    {
        return new LinkedQueueNode<E>();
    }

    protected final LinkedQueueNode<E> newNode(E e)
    {
        return new LinkedQueueNode<E>(e);
    }

    /**
     * {@inheritDoc} <br>
     * <p>
     * IMPLEMENTATION NOTES:<br>
     * This is an O(n) operation as we run through all the nodes and count them.<br>
     * The accuracy of the value returned by this method is subject to races with producer/consumer threads. In
     * particular when racing with the consumer thread this method may under estimate the size.<br>
     *
     * @see Queue#size()
     */
    @Override
    public final int size()
    {
        // Read consumer first, this is important because if the producer is node is 'older' than the consumer
        // the consumer may overtake it (consume past it) invalidating the 'snapshot' notion of size.
        LinkedQueueNode<E> chaserNode = lvConsumerNode();
        LinkedQueueNode<E> producerNode = lvProducerNode();
        int size = 0;
        // must chase the nodes all the way to the producer node, but there's no need to count beyond expected head.
        while (chaserNode != producerNode && // don't go passed producer node
            chaserNode != null && // stop at last node
            size < Integer.MAX_VALUE) // stop at max int
        {
            LinkedQueueNode<E> next;
            next = chaserNode.lvNext();
            // check if this node has been consumed, if so return what we have
            if (next == chaserNode)
            {
                return size;
            }
            chaserNode = next;
            size++;
        }
        return size;
    }

    /**
     * {@inheritDoc} <br>
     * <p>
     * IMPLEMENTATION NOTES:<br>
     * Queue is empty when producerNode is the same as consumerNode. An alternative implementation would be to
     * observe the producerNode.value is null, which also means an empty queue because only the
     * consumerNode.value is allowed to be null.
     *
     * @see MessagePassingQueue#isEmpty()
     */
    @Override
    public boolean isEmpty()
    {
        LinkedQueueNode<E> consumerNode = lvConsumerNode();
        LinkedQueueNode<E> producerNode = lvProducerNode();
        return consumerNode == producerNode;
    }

    protected E getSingleConsumerNodeValue(LinkedQueueNode<E> currConsumerNode, LinkedQueueNode<E> nextNode)
    {
        // we have to null out the value because we are going to hang on to the node
        final E nextValue = nextNode.getAndNullValue();

        // Fix up the next ref of currConsumerNode to prevent promoted nodes from keeping new ones alive.
        // We use a reference to self instead of null because null is already a meaningful value (the next of
        // producer node is null).
        currConsumerNode.soNext(currConsumerNode);
        spConsumerNode(nextNode);
        // currConsumerNode is now no longer referenced and can be collected
        return nextValue;
    }

    /**
     * {@inheritDoc} <br>
     * <p>
     * IMPLEMENTATION NOTES:<br>
     * Poll is allowed from a SINGLE thread.<br>
     * Poll is potentially blocking here as the {@link Queue#poll()} does not allow returning {@code null} if the queue is not
     * empty. This is very different from the original Vyukov guarantees. See {@link #relaxedPoll()} for the original
     * semantics.<br>
     * Poll reads {@code consumerNode.next} and:
     * <ol>
     * <li>If it is {@code null} AND the queue is empty return {@code null}, <b>if queue is not empty spin wait for
     * value to become visible</b>.
     * <li>If it is not {@code null} set it as the consumer node and return it's now evacuated value.
     * </ol>
     * This means the consumerNode.value is always {@code null}, which is also the starting point for the queue.
     * Because {@code null} values are not allowed to be offered this is the only node with it's value set to
     * {@code null} at any one time.
     *
     * @see MessagePassingQueue#poll()
     * @see Queue#poll()
     */
    @Override
    public E poll()
    {
        final LinkedQueueNode<E> currConsumerNode = lpConsumerNode();
        LinkedQueueNode<E> nextNode = currConsumerNode.lvNext();
        if (nextNode != null)
        {
            return getSingleConsumerNodeValue(currConsumerNode, nextNode);
        }
        else if (currConsumerNode != lvProducerNode())
        {
            nextNode = spinWaitForNextNode(currConsumerNode);
            // got the next node...
            return getSingleConsumerNodeValue(currConsumerNode, nextNode);
        }
        return null;
    }

    /**
     * {@inheritDoc} <br>
     * <p>
     * IMPLEMENTATION NOTES:<br>
     * Peek is allowed from a SINGLE thread.<br>
     * Peek is potentially blocking here as the {@link Queue#peek()} does not allow returning {@code null} if the queue is not
     * empty. This is very different from the original Vyukov guarantees. See {@link #relaxedPeek()} for the original
     * semantics.<br>
     * Poll reads the next node from the consumerNode and:
     * <ol>
     * <li>If it is {@code null} AND the queue is empty return {@code null}, <b>if queue is not empty spin wait for
     * value to become visible</b>.
     * <li>If it is not {@code null} return it's value.
     * </ol>
     *
     * @see MessagePassingQueue#peek()
     * @see Queue#peek()
     */
    @Override
    public E peek()
    {
        final LinkedQueueNode<E> currConsumerNode = lpConsumerNode();
        LinkedQueueNode<E> nextNode = currConsumerNode.lvNext();
        if (nextNode != null)
        {
            return nextNode.lpValue();
        }
        else if (currConsumerNode != lvProducerNode())
        {
            nextNode = spinWaitForNextNode(currConsumerNode);
            // got the next node...
            return nextNode.lpValue();
        }
        return null;
    }

    LinkedQueueNode<E> spinWaitForNextNode(LinkedQueueNode<E> currNode)
    {
        LinkedQueueNode<E> nextNode;
        while ((nextNode = currNode.lvNext()) == null)
        {
            // spin, we are no longer wait free
        }
        return nextNode;
    }

    @Override
    public E relaxedPoll()
    {
        final LinkedQueueNode<E> currConsumerNode = lpConsumerNode();
        final LinkedQueueNode<E> nextNode = currConsumerNode.lvNext();
        if (nextNode != null)
        {
            return getSingleConsumerNodeValue(currConsumerNode, nextNode);
        }
        return null;
    }

    @Override
    public E relaxedPeek()
    {
        final LinkedQueueNode<E> nextNode = lpConsumerNode().lvNext();
        if (nextNode != null)
        {
            return nextNode.lpValue();
        }
        return null;
    }

    @Override
    public boolean relaxedOffer(E e)
    {
        return offer(e);
    }

    @Override
    public int drain(Consumer<E> c, int limit)
    {
        if (null == c)
            throw new IllegalArgumentException("c is null");
        if (limit < 0)
            throw new IllegalArgumentException("limit is negative: " + limit);
        if (limit == 0)
            return 0;

        LinkedQueueNode<E> chaserNode = this.lpConsumerNode();
        for (int i = 0; i < limit; i++)
        {
            final LinkedQueueNode<E> nextNode = chaserNode.lvNext();

            if (nextNode == null)
            {
                return i;
            }
            // we have to null out the value because we are going to hang on to the node
            final E nextValue = getSingleConsumerNodeValue(chaserNode, nextNode);
            chaserNode = nextNode;
            c.accept(nextValue);
        }
        return limit;
    }

    @Override
    public int drain(Consumer<E> c)
    {
        return MessagePassingQueueUtil.drain(this, c);
    }

    @Override
    public void drain(Consumer<E> c, WaitStrategy wait, ExitCondition exit)
    {
        MessagePassingQueueUtil.drain(this, c, wait, exit);
    }

    @Override
    public int capacity()
    {
        return UNBOUNDED_CAPACITY;
    }

}
