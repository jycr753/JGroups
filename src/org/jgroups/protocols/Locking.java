package org.jgroups.protocols;

import org.jgroups.*;
import org.jgroups.annotations.MBean;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.annotations.ManagedOperation;
import org.jgroups.annotations.Property;
import org.jgroups.blocks.locking.LockInfo;
import org.jgroups.blocks.locking.LockNotification;
import org.jgroups.blocks.locking.Owner;
import org.jgroups.stack.Protocol;
import org.jgroups.util.Streamable;
import org.jgroups.util.Util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;



/**
 * Base locking protocol, handling most of the protocol communication with other instances. To use distributed locking,
 * {@link org.jgroups.blocks.locking.LockService} is placed on a channel. LockService talks to a subclass of Locking
 * via events.
 * @author Bela Ban
 * @since 2.12
 * @see org.jgroups.protocols.CENTRAL_LOCK
 * @see org.jgroups.protocols.PEER_LOCK
 */
@MBean(description="Based class for locking functionality")
abstract public class Locking extends Protocol {

    @Property(description="bypasses message bundling if set")
    protected boolean bypass_bundling=true;


    protected Address local_addr;

    protected View view;

    // server side locks
    protected final ConcurrentMap<String,ServerLock> server_locks=Util.createConcurrentMap(20);

    // client side locks
    protected final Map<String,Map<Owner,ClientLock>> client_locks=new HashMap<String,Map<Owner,ClientLock>>();

    protected final Set<LockNotification> lock_listeners=new HashSet<LockNotification>();



    protected static enum Type {
        GRANT_LOCK,    // request to acquire a lock
        LOCK_GRANTED,  // response to sender of GRANT_LOCK on succcessful lock acquisition
        LOCK_DENIED,   // response to sender of GRANT_LOCK on unsuccessful lock acquisition (e.g. on tryLock())
        RELEASE_LOCK,  // request to release a lock
        CREATE_LOCK,   // request to create a server lock (sent by coordinator to backups). Used by CentralLockService
        DELETE_LOCK    // request to delete a server lock (sent by coordinator to backups). Used by CentralLockService
    }



    public Locking() {
    }


    public boolean getBypassBundling() {
        return bypass_bundling;
    }

    public void setBypassBundling(boolean bypass_bundling) {
        this.bypass_bundling=bypass_bundling;
    }

    public void addLockListener(LockNotification listener) {
        if(listener != null)
            lock_listeners.add(listener);
    }

    public void removeLockListener(LockNotification listener) {
        if(listener != null)
            lock_listeners.remove(listener);
    }

    @ManagedAttribute
    public String getAddress() {
        return local_addr != null? local_addr.toString() : null;
    }

    @ManagedAttribute
    public String getView() {
        return view != null? view.toString() : null;
    }
   


    public Object down(Event evt) {
        switch(evt.getType()) {
            case Event.LOCK:
                LockInfo info=(LockInfo)evt.getArg();
                ClientLock lock=getLock(info.getName());
                if(!info.isTrylock()) {
                    if(info.isLockInterruptibly()) {
                        try {
                            lock.lockInterruptibly();
                        }
                        catch(InterruptedException e) {
                            Thread.currentThread().interrupt(); // has to be checked by caller who has to rethrow ...
                        }
                    }
                    else
                        lock.lock();
                }
                else {
                    if(info.isUseTimeout()) {
                        try {
                            return lock.tryLock(info.getTimeout(), info.getTimeUnit());
                        }
                        catch(InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    else {
                        return lock.tryLock();
                    }
                }
                return null;


            case Event.UNLOCK:
                info=(LockInfo)evt.getArg();
                lock=getLock(info.getName(), false);
                if(lock != null)
                    lock.unlock();
                return null;

            case Event.UNLOCK_ALL:
                unlockAll();
                return null;
            case Event.SET_LOCAL_ADDRESS:
                local_addr=(Address)evt.getArg();
                break;

            case Event.VIEW_CHANGE:
                handleView((View)evt.getArg());
                break;
        }
        return down_prot.down(evt);
    }

    public Object up(Event evt) {
        switch(evt.getType()) {
            case Event.MSG:
                Message msg=(Message)evt.getArg();
                LockingHeader hdr=(LockingHeader)msg.getHeader(id);
                if(hdr == null)
                    break;

                Request req=(Request)msg.getObject();
                if(log.isTraceEnabled())
                    log.trace("[" + local_addr + "] <-- [" + msg.getSrc() + "] " + req);
                switch(req.type) {
                    case GRANT_LOCK:
                    case RELEASE_LOCK:
                        handleLockRequest(req);
                        break;
                    case LOCK_GRANTED:
                        handleLockGrantedResponse(req.lock_name, req.owner, msg.getSrc());
                        break;
                    case LOCK_DENIED:
                        handleLockDeniedResponse(req.lock_name, req.owner);
                        break;
                    case CREATE_LOCK:
                        handleCreateLockRequest(req.lock_name, req.owner);
                        break;
                    case DELETE_LOCK:
                        handleDeleteLockRequest(req.lock_name);
                        break;
                    default:
                        log.error("Request of type " + req.type + " not known");
                        break;
                }
                return null;

            case Event.VIEW_CHANGE:
                handleView((View)evt.getArg());
                break;
        }
        return up_prot.up(evt);
    }

    protected ClientLock getLock(String name) {
        return getLock(name, getOwner(), true);
    }

    protected ClientLock getLock(String name, boolean create_if_absent) {
        return getLock(name, getOwner(), create_if_absent);
    }

    @ManagedOperation(description="Unlocks all currently held locks")
    public void unlockAll() {
        List<ClientLock> locks=new ArrayList<ClientLock>();
        synchronized(client_locks) {
            Collection<Map<Owner,ClientLock>> maps=client_locks.values();
            for(Map<Owner,ClientLock> map: maps) {
                locks.addAll(map.values());
            }
        }
        for(ClientLock lock: locks)
            lock.unlock();
    }


    @ManagedOperation(description="Dumps all locks")
    public String printLocks() {
        StringBuilder sb=new StringBuilder();
        sb.append("server locks:\n");
        for(Map.Entry<String,ServerLock> entry: server_locks.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }

        sb.append("\nmy locks: ");
        synchronized(client_locks) {
            boolean first_element=true;
            for(Map.Entry<String,Map<Owner,ClientLock>> entry: client_locks.entrySet()) {
                if(first_element)
                    first_element=false;
                else
                    sb.append(", ");
                sb.append(entry.getKey()).append(" (");
                Map<Owner,ClientLock> owners=entry.getValue();
                boolean first=true;
                for(Map.Entry<Owner,ClientLock> entry2: owners.entrySet()) {
                    if(first)
                        first=false;
                    else
                        sb.append(", ");
                    sb.append(entry2.getKey());
                    ClientLock cl=entry2.getValue();
                    if(!cl.acquired || cl.denied)
                        sb.append(", unlocked");
                }
                sb.append(")");
            }
        }
        return sb.toString();
    }

    protected void handleView(View view) {
        this.view=view;
        if(log.isDebugEnabled())
            log.debug("view=" + view);
        List<Address> members=view.getMembers();
        for(Map.Entry<String,ServerLock> entry: server_locks.entrySet()) {
            entry.getValue().handleView(members);
        }
        for(Map.Entry<String,ServerLock> entry: server_locks.entrySet()) {
            ServerLock lock=entry.getValue();
            if(lock.isEmpty() && lock.current_owner == null)
                server_locks.remove(entry.getKey());
        }
    }



    protected ClientLock createLock(String lock_name) {
        return new ClientLock(lock_name);
    }

    protected Owner getOwner() {
        return new Owner(local_addr, Thread.currentThread().getId());
    }

    abstract protected void sendGrantLockRequest(String lock_name, Owner owner, long timeout, boolean is_trylock);
    abstract protected void sendReleaseLockRequest(String lock_name, Owner owner);


    protected void sendRequest(Address dest, Type type, String lock_name, Owner owner, long timeout, boolean is_trylock) {
        Request req=new Request(type, lock_name, owner, timeout, is_trylock);
        Message msg=new Message(dest, null, req);
        msg.putHeader(id, new LockingHeader());
        if(bypass_bundling)
            msg.setFlag(Message.DONT_BUNDLE);
        if(log.isTraceEnabled())
            log.trace("[" + local_addr + "] --> [" + (dest == null? "ALL" : dest) + "] " + req);
        try {
            down_prot.down(new Event(Event.MSG, msg));
        }
        catch(Exception ex) {
            log.error("failed sending " + type + " request: " + ex);
        }
    }


    protected void sendLockResponse(Type type, Owner dest, String lock_name) {
        Request rsp=new Request(type, lock_name, dest, 0);
        Message lock_granted_rsp=new Message(dest.getAddress(), null, rsp);
        lock_granted_rsp.putHeader(id, new LockingHeader());
        if(bypass_bundling)
            lock_granted_rsp.setFlag(Message.DONT_BUNDLE);

        if(log.isTraceEnabled())
            log.trace("[" + local_addr + "] --> [" + dest.getAddress() + "] " + rsp);

        try {
            down_prot.down(new Event(Event.MSG, lock_granted_rsp));
        }
        catch(Exception ex) {
            log.error("failed sending " + type + " message to " + dest + ": " + ex);
        }
    }


    protected void handleLockRequest(Request req) {
        ServerLock lock=server_locks.get(req.lock_name);
        if(lock == null) {
            lock=new ServerLock(req.lock_name);
            ServerLock tmp=server_locks.putIfAbsent(req.lock_name, lock);
            if(tmp != null)
                lock=tmp;
            else
                notifyLockCreated(req.lock_name);
        }
        lock.handleRequest(req);
        if(lock.isEmpty() && lock.current_owner == null)
            server_locks.remove(req.lock_name);
    }


    protected void handleLockGrantedResponse(String lock_name, Owner owner, Address sender) {
        ClientLock lock=getLock(lock_name, owner, false);
        if(lock != null)
            lock.handleLockGrantedResponse(owner, sender);
    }

    protected void handleLockDeniedResponse(String lock_name, Owner owner) {
         ClientLock lock=getLock(lock_name, owner, false);
         if(lock != null)
             lock.lockDenied();
    }

    protected void handleCreateLockRequest(String lock_name, Owner owner) {
        synchronized(server_locks) {
            server_locks.put(lock_name, new ServerLock(lock_name, owner));
        }
    }


    protected void handleDeleteLockRequest(String lock_name) {
        synchronized(server_locks) {
            server_locks.remove(lock_name);
        }
    }


    protected ClientLock getLock(String name, Owner owner, boolean create_if_absent) {
        synchronized(client_locks) {
            Map<Owner,ClientLock> owners=client_locks.get(name);
            if(owners == null) {
                if(!create_if_absent)
                    return null;
                owners=new HashMap<Owner,ClientLock>();
                client_locks.put(name, owners);
            }
            ClientLock lock=owners.get(owner);
            if(lock == null) {
                if(!create_if_absent)
                    return null;
                lock=createLock(name);
                owners.put(owner, lock);
            }
            return lock;
        }
    }

    protected void removeClientLock(String lock_name, Owner owner) {
        synchronized(client_locks) {
            Map<Owner,ClientLock> owners=client_locks.get(lock_name);
            if(owners != null) {
                ClientLock lock=owners.remove(owner);
                if(lock != null) {
                    if(owners.isEmpty())
                        client_locks.remove(lock_name);
                }
            }
        }
    }


    protected void notifyLockCreated(String lock_name) {
        for(LockNotification listener: lock_listeners) {
            try {
                listener.lockCreated(lock_name);
            }
            catch(Throwable t) {
                log.error("failed notifying " + listener, t);
            }
        }
    }

    protected void notifyLockDeleted(String lock_name) {
        for(LockNotification listener: lock_listeners) {
            try {
                listener.lockDeleted(lock_name);
            }
            catch(Throwable t) {
                log.error("failed notifying " + listener, t);
            }
        }
    }

    protected void notifyLocked(String lock_name, Owner owner) {
        for(LockNotification listener: lock_listeners) {
            try {
                listener.locked(lock_name, owner);
            }
            catch(Throwable t) {
                log.error("failed notifying " + listener, t);
            }
        }
    }

    protected void notifyUnlocked(String lock_name, Owner owner) {
        for(LockNotification listener: lock_listeners) {
            try {
                listener.unlocked(lock_name, owner);
            }
            catch(Throwable t) {
                log.error("failed notifying " + listener, t);
            }
        }
    }




    /**
     * Server side queue for handling of lock requests (lock, release).
     * @author Bela Ban
     */
    protected class ServerLock {
        protected final String lock_name;
        protected Owner current_owner;
        protected final List<Request> queue=new ArrayList<Request>();

        public ServerLock(String lock_name) {
            this.lock_name=lock_name;
        }

        protected ServerLock(String lock_name, Owner owner) {
            this.lock_name=lock_name;
            this.current_owner=owner;
        }

        protected synchronized void handleRequest(Request req) {
            switch(req.type) {
                case GRANT_LOCK:
                    if(current_owner == null) {
                        setOwner(req.owner);
                        sendLockResponse(Type.LOCK_GRANTED, req.owner, req.lock_name);
                    }
                    else {
                        if(current_owner.equals(req.owner)) {
                            sendLockResponse(Type.LOCK_GRANTED, req.owner, req.lock_name);
                        }
                        else {
                            if(req.is_trylock && req.timeout <= 0)
                                sendLockResponse(Type.LOCK_DENIED, req.owner, req.lock_name);
                            else
                                addToQueue(req);
                        }
                    }
                    break;
                case RELEASE_LOCK:
                    if(current_owner == null)
                        break;
                    if(current_owner.equals(req.owner))
                        setOwner(null);
                    else
                        addToQueue(req);
                    break;
                default:
                    throw new IllegalArgumentException("type " + req.type + " is invalid here");
            }

            processQueue();
        }

        protected synchronized void handleView(List<Address> members) {
            if(current_owner != null && !members.contains(current_owner.getAddress())) {
                Owner tmp=current_owner;
                setOwner(null);
                if(log.isDebugEnabled())
                    log.debug("unlocked \"" + lock_name + "\" because owner " + tmp + " left");
            }

            for(Iterator<Request> it=queue.iterator(); it.hasNext();) {
                Request req=it.next();
                if(!members.contains(req.owner.getAddress()))
                    it.remove();
            }

            processQueue();
        }


        protected void addToQueue(Request req) {
            if(queue.isEmpty()) {
                if(req.type == Type.GRANT_LOCK)
                    queue.add(req);
                return; // RELEASE_LOCK is discarded on an empty queue
            }

            // at this point the queue is not empty
            switch(req.type) {

                // If there is already a lock request from the same owner, discard the new lock request
                case GRANT_LOCK:
                    if(!isRequestPresent(Type.GRANT_LOCK, req.owner))
                        queue.add(req);
                    break;

                case RELEASE_LOCK:
                    // Release the lock request from the same owner already in the queue
                    // If there is no lock request, discard the unlock request
                    removeRequest(Type.GRANT_LOCK, req.owner);
                    break;
            }
        }

        /** Checks if a certain request from a given owner is already in the queue */
        protected boolean isRequestPresent(Type type, Owner owner) {
            for(Request req: queue)
                if(req.type == type && req.owner.equals(owner))
                    return true;
            return false;
        }

        protected void removeRequest(Type type, Owner owner) {
            for(Iterator<Request> it=queue.iterator(); it.hasNext();) {
                Request req=it.next();
                if(req.type == type && req.owner.equals(owner))
                    it.remove();
            }
        }


        protected void processQueue() {
            if(current_owner == null) {
                while(!queue.isEmpty()) {
                    Request req=queue.remove(0);
                    if(req.type == Type.GRANT_LOCK) {
                        setOwner(req.owner);
                        sendLockResponse(Type.LOCK_GRANTED, req.owner, req.lock_name);
                        break;
                    }
                }
            }
        }

        protected void setOwner(Owner owner) {
            if(owner == null) {
                if(current_owner != null) {
                    Owner tmp=current_owner;
                    current_owner=null;
                    notifyUnlocked(lock_name, tmp);
                }
            }
            else {
                current_owner=owner;
                notifyLocked(lock_name, owner);
            }
        }

        public boolean isEmpty() {return queue.isEmpty();}

        public String toString() {
            StringBuilder sb=new StringBuilder();
            sb.append(current_owner);
            if(!queue.isEmpty()) {
                sb.append(", queue: ");
                for(Request req: queue) {
                    sb.append(req.toStringShort()).append(" ");
                }
            }
            return sb.toString();
        }
    }




    protected class ClientLock implements Lock {
        protected final String      name;
        protected Owner             owner;
        protected volatile boolean  acquired;
        protected volatile boolean  denied;
        protected volatile boolean  is_trylock;
        protected long              timeout;

        public ClientLock(String name) {
            this.name=name;
        }

        public void lock() {
            try {
                acquire(false);
            }
            catch(InterruptedException e) {
                // This should never happen
            }
        }

        public void lockInterruptibly() throws InterruptedException {
            acquire(true);
        }

        public boolean tryLock() {
            try {
                return acquireTryLock(0, false);
            }
            catch(InterruptedException e) {
                return false;
            }
        }

        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return acquireTryLock(TimeUnit.MILLISECONDS.convert(time, unit), true);
        }

        public synchronized void unlock() {
            _unlock(false);
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException("currently not implemented");
        }

        public String toString() {
            return name + " (locked=" + acquired +")";
        }

        protected synchronized void lockGranted() {
            acquired=true;
            this.notifyAll();
        }

        protected synchronized void lockDenied() {
            denied=true;
            this.notifyAll();
        }

        protected void handleLockGrantedResponse(Owner owner, Address sender) {
            lockGranted();
        }

        protected synchronized void acquire(boolean throwInterrupt) throws InterruptedException {
            if(!acquired) {
                owner=getOwner();
                sendGrantLockRequest(name, owner, 0, false);
                boolean interrupted=false;
                while(!acquired) {
                    try {
                        this.wait();
                    }
                    catch (InterruptedException e) {
                        // If we haven't acquired the lock yet and were interrupted, then we have to clean up the lock
                        // request and throw the exception
                        if (throwInterrupt && !acquired) {
                            _unlock(true);
                            throw e;
                        }
                        // If we did get the lock then we will return with the lock and interrupt status.
                        // If we don't throw exceptions then we just set the interrupt flag and let it loop around
                        interrupted=true;
                    }
                }
                if(interrupted)
                    Thread.currentThread().interrupt();
            }
        }

        protected synchronized void _unlock(boolean force) {
            if(!acquired && !denied && !force)
                return;
            this.timeout=0;
            this.is_trylock=false;
            sendReleaseLockRequest(name, owner);
            acquired=denied=false;
            notifyAll();

            removeClientLock(name, owner);
            notifyLockDeleted(name);
            owner=null;
        }

        protected synchronized boolean acquireTryLock(long timeout, boolean use_timeout) throws InterruptedException {
            if(denied)
                return false;
            if(!acquired) {
                is_trylock=true;
                this.timeout=timeout;
                owner=getOwner();
                sendGrantLockRequest(name, owner, timeout, true);

                long target_time=use_timeout? System.currentTimeMillis() + timeout : 0;
                boolean interrupted = false;
                while(!acquired && !denied) {
                    if(use_timeout) {
                        long wait_time=target_time - System.currentTimeMillis();
                        if(wait_time <= 0)
                            break;
                        else {
                            this.timeout=wait_time;
                            try {
                                this.wait(wait_time);
                            }
                            catch (InterruptedException e) {
                                // If we were interrupted and haven't received a response yet then we try to
                                // clean up the lock request and throw the exception
                                if (!acquired && !denied) {
                                    _unlock(true);
                                    throw e;
                                }
                                // In the case that we were told if we acquired or denied the lock then return that, but
                                // make sure we set the interrupt status
                                interrupted = true;
                            }
                        }
                    }
                    else {
                        try {
                            this.wait();
                        }
                        catch(InterruptedException e) {
                            interrupted = true;
                        }
                    }
                }
                if(interrupted)
                    Thread.currentThread().interrupt();
            }
            if(!acquired || denied)
                _unlock(true);
            return acquired && !denied;
        }
    }


    protected static class Request implements Streamable {
        protected Type    type;
        protected String  lock_name;
        protected Owner   owner;
        protected long    timeout=0;
        protected boolean is_trylock;


        public Request() {
        }

        public Request(Type type, String lock_name, Owner owner, long timeout) {
            this.type=type;
            this.lock_name=lock_name;
            this.owner=owner;
            this.timeout=timeout;
        }

        public Request(Type type, String lock_name, Owner owner, long timeout, boolean is_trylock) {
            this(type, lock_name, owner, timeout);
            this.is_trylock=is_trylock;
        }

        public void writeTo(DataOutputStream out) throws IOException {
            out.writeByte(type.ordinal());
            Util.writeString(lock_name, out);
            Util.writeStreamable(owner, out);
            out.writeLong(timeout);
            out.writeBoolean(is_trylock);
        }

        public void readFrom(DataInputStream in) throws IOException, IllegalAccessException, InstantiationException {
            type=Type.values()[in.readByte()];
            lock_name=Util.readString(in);
            owner=(Owner)Util.readStreamable(Owner.class, in);
            timeout=in.readLong();
            is_trylock=in.readBoolean();
        }

        public String toString() {
            return type.name() + " [" + lock_name + ", owner=" + owner + (is_trylock? ", trylock " : " ") +
              (timeout > 0? "(timeout=" + timeout + ")" : "" + "]");
        }

        public String toStringShort() {
            StringBuilder sb=new StringBuilder();
            switch(type) {
                case RELEASE_LOCK:
                    sb.append("U");
                    break;
                case GRANT_LOCK:
                    sb.append(is_trylock? "TL" : "L");
                    break;
                default:
                    sb.append("N/A");
                    break;
            }
            sb.append("(").append(lock_name).append(",").append(owner);
            if(timeout > 0)
                sb.append(",").append(timeout);
            sb.append(")");
            return sb.toString();
        }
    }


    public static class LockingHeader extends Header {

        public LockingHeader() {
        }

        public int size() {
            return 0;
        }

        public void writeTo(DataOutputStream out) throws IOException {
        }

        public void readFrom(DataInputStream in) throws IOException, IllegalAccessException, InstantiationException {
        }
    }

}