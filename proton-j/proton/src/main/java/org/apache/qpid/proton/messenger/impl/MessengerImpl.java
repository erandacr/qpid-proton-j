/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
*/
package org.apache.qpid.proton.messenger.impl;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.qpid.proton.ProtonFactoryLoader;
import org.apache.qpid.proton.TimeoutException;
import org.apache.qpid.proton.driver.Connector;
import org.apache.qpid.proton.driver.Driver;
import org.apache.qpid.proton.driver.DriverFactory;
import org.apache.qpid.proton.driver.Listener;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.Delivery;
import org.apache.qpid.proton.engine.EndpointState;
import org.apache.qpid.proton.engine.EngineFactory;
import org.apache.qpid.proton.engine.Link;
import org.apache.qpid.proton.engine.Receiver;
import org.apache.qpid.proton.engine.Sasl;
import org.apache.qpid.proton.engine.Sender;
import org.apache.qpid.proton.engine.Session;
import org.apache.qpid.proton.message.Message;
import org.apache.qpid.proton.message.MessageFactory;
import org.apache.qpid.proton.messenger.Messenger;
import org.apache.qpid.proton.messenger.MessengerException;
import org.apache.qpid.proton.messenger.MessengerFactory;
import org.apache.qpid.proton.messenger.Status;
import org.apache.qpid.proton.messenger.Tracker;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.amqp.messaging.Target;

public class MessengerImpl implements Messenger
{
    private static ProtonFactoryLoader protonFactoryLoader = new ProtonFactoryLoader();

    private static final EnumSet<EndpointState> UNINIT = EnumSet.of(EndpointState.UNINITIALIZED);
    private static final EnumSet<EndpointState> ACTIVE = EnumSet.of(EndpointState.ACTIVE);
    private static final EnumSet<EndpointState> CLOSED = EnumSet.of(EndpointState.CLOSED);
    private static final EnumSet<EndpointState> ANY = EnumSet.allOf(EndpointState.class);
    private static final Accepted ACCEPTED = new Accepted();

    private final Logger _logger = Logger.getLogger("proton.messenger");
    private final String _name;
    private final EngineFactory _engineFactory;
    private final DriverFactory _driverFactory;
    private final MessageFactory _messageFactory;
    private long _timeout = -1;
    private long _nextTag = 1;
    private byte[] _buffer = new byte[5*1024];
    private Driver _driver;
    private boolean _unlimitedCredit = false;
    private static final int _creditBatch = 10;
    private int _credit;
    private int _distributed;
    private TrackerQueue _incoming = new TrackerQueue();
    private TrackerQueue _outgoing = new TrackerQueue();


    /**
     * @deprecated This constructor's visibility will be reduced to the default scope in a future release.
     * Client code outside this module should use a {@link MessengerFactory} instead
     */
    @Deprecated public MessengerImpl()
    {
        this(java.util.UUID.randomUUID().toString());
    }

    /**
     * @deprecated This constructor's visibility will be reduced to the default scope in a future release.
     * Client code outside this module should use a {@link MessengerFactory} instead
     */
    @Deprecated public MessengerImpl(String name)
    {
        this(name, defaultEngineFactory(), defaultDriverFactory(), defaultMessageFactory());
    }

    /**
     * @deprecated This constructor's visibility will be reduced to the default scope in a future release.
     * Client code outside this module should use a {@link MessengerFactory} instead
     */
    @Deprecated public MessengerImpl(String name, EngineFactory engineFactory, DriverFactory driverFactory, MessageFactory messageFactory)
    {
        _name = name;
        _engineFactory = engineFactory;
        _driverFactory = driverFactory;
        _messageFactory = messageFactory;
    }

    public void setTimeout(long timeInMillis)
    {
        _timeout = timeInMillis;
    }

    public long getTimeout()
    {
        return _timeout;
    }

    public void start() throws IOException
    {
        _driver = _driverFactory.createDriver();
    }

    public void stop()
    {
        //close all connections
        for (Connector c : _driver.connectors())
        {
            Connection connection = c.getConnection();
            connection.close();
            try
            {
                c.process();
            }
            catch (IOException e)
            {
                _logger.log(Level.WARNING, "Error while sending close", e);
            }
        }
        //stop listeners
        for (Listener l : _driver.listeners())
        {
            try
            {
                l.close();
            }
            catch (IOException e)
            {
                _logger.log(Level.WARNING, "Error while closing listener", e);
            }
        }
        try
        {
            waitUntil(_allClosed);
        }
        catch(TimeoutException e)
        {
            _logger.log(Level.WARNING, "Timed out while waiting for close", e);
        }
        _driver.destroy();
    }

    public void put(Message m) throws MessengerException
    {
        try
        {
            URI address = new URI(m.getAddress());
            if (address.getHost() == null)
            {
                throw new MessengerException("unable to send to address: " + m.getAddress());
            }
            int port = address.getPort() < 0 ? defaultPort(address.getScheme()) : address.getPort();
            Sender sender = getLink(address.getHost(), port, new SenderFinder(cleanPath(address.getPath())));

            adjustReplyTo(m);

            byte[] tag = String.valueOf(_nextTag++).getBytes();
            Delivery delivery = sender.delivery(tag);
            int encoded;
            while (true)
            {
                try
                {
                    encoded = m.encode(_buffer, 0, _buffer.length);
                    break;
                } catch (java.nio.BufferOverflowException e) {
                    _buffer = new byte[_buffer.length*2];
                }
            }
            sender.send(_buffer, 0, encoded);
            _outgoing.add(delivery);
            sender.advance();
        }
        catch (URISyntaxException e)
        {
            throw new MessengerException("Invalid address: " + m.getAddress(), e);
        }
    }

    public void send() throws TimeoutException
    {
        waitUntil(_sentSettled);
    }

    public void recv(int n) throws TimeoutException
    {
        if (n == -1) {
            _unlimitedCredit = true;
        } else {
            _credit += n;
            _unlimitedCredit = false;
        }
        distributeCredit();

        waitUntil(_messageAvailable);
    }

    public Message get()
    {
        for (Connector c : _driver.connectors())
        {
            Connection connection = c.getConnection();
            _logger.log(Level.FINE, "Attempting to get message from " + connection);
            Delivery delivery = connection.getWorkHead();
            while (delivery != null)
            {
                if (delivery.isReadable() && !delivery.isPartial())
                {
                    _logger.log(Level.FINE, "Readable delivery found: " + delivery);
                    int size = read((Receiver) delivery.getLink());
                    Message message = _messageFactory.createMessage();
                    message.decode(_buffer, 0, size);
                    _incoming.add(delivery);
                    _distributed--;
                    delivery.getLink().advance();
                    return message;
                }
                else
                {
                    _logger.log(Level.FINE, "Delivery not readable: " + delivery);
                    delivery = delivery.getWorkNext();
                }
            }
        }
        return null;
    }

    public void subscribe(String source) throws MessengerException
    {
        //the following is not safe or accurate, but it appears '~' is
        //invalid as the start of the hostname and URI can't handle
        //it, so this is a quick hack to avoid rewriting the parsing
        //logic for URLs right now...
        boolean listen = source.contains("~");
        try
        {
            URI address = new URI(listen ? source.replace("~", "") : source);
            if (address.getHost() == null) throw new MessengerException("Invalid source address (hostname cannot be null): " + source);
            int port = address.getPort() < 0 ? defaultPort(address.getScheme()) : address.getPort();
            if (listen)
            {
                _driver.createListener(address.getHost(), port, null);
            }
            else
            {
                getLink(address.getHost(), port, new ReceiverFinder(cleanPath(address.getPath())));
            }
        }
        catch (URISyntaxException e)
        {
            throw new MessengerException("Invalid source: " + source, e);
        }

    }

    public int outgoing()
    {
        return queued(true);
    }

    public int incoming()
    {
        return queued(false);
    }


    public int getIncomingWindow()
    {
        return _incoming.getWindow();
    }
    public void setIncomingWindow(int window)
    {
        _incoming.setWindow(window);
    }

    public int getOutgoingWindow()
    {
        return _outgoing.getWindow();
    }
    public void setOutgoingWindow(int window)
    {
        _outgoing.setWindow(window);
    }

    public Tracker incomingTracker()
    {
        return new TrackerImpl(false, _incoming.getHighWaterMark() - 1);
    }
    public Tracker outgoingTracker()
    {
        return new TrackerImpl(true, _outgoing.getHighWaterMark() - 1);
    }

    private TrackerQueue getTrackerQueue(Tracker tracker)
    {
        return TrackerQueue.isOutgoing(tracker) ? _outgoing : _incoming;
    }
    public void reject(Tracker tracker, int flags)
    {
        getTrackerQueue(tracker).reject(tracker, flags);
    }
    public void accept(Tracker tracker, int flags)
    {
        getTrackerQueue(tracker).accept(tracker, flags);
    }
    public void settle(Tracker tracker, int flags)
    {
        getTrackerQueue(tracker).settle(tracker, flags);
    }

    public Status getStatus(Tracker tracker)
    {
        return getTrackerQueue(tracker).getStatus(tracker);
    }

    private int queued(boolean outgoing)
    {
        int count = 0;
        for (Connector c : _driver.connectors())
        {
            Connection connection = c.getConnection();
            for (Link link : new Links(connection, ACTIVE, ANY))
            {
                if (outgoing)
                {
                    if (link instanceof Sender) count += link.getQueued();
                }
                else
                {
                    if (link instanceof Receiver) count += link.getQueued();
                }
            }
        }
        return count;
    }

    private int read(Receiver receiver)
    {
        //TODO: add pending count to Delivery?
        int total = 0;
        int start = 0;
        while (true)
        {
            int read = receiver.recv(_buffer, start, _buffer.length - start);
            total += read;
            if (read == (_buffer.length - start))
            {
                //may need to expand the buffer (is there a better test?)
                byte[] old = _buffer;
                _buffer = new byte[_buffer.length*2];
                System.arraycopy(old, 0, _buffer, 0, old.length);
                start += read;
            }
            else
            {
                break;
            }
        }
        return total;
    }

    private void process()
    {
        processAllConnectors();
        processActive();
    }

    private void processAllConnectors()
    {
        for (Connector c : _driver.connectors())
        {
            try
            {
                c.process();
            }
            catch (IOException e)
            {
                _logger.log(Level.SEVERE, "Error processing connection", e);
            }
        }
    }

    private void processActive()
    {
        //process active listeners
        for (Listener l = _driver.listener(); l != null; l = _driver.listener())
        {
            Connector c = l.accept();
            Connection connection = _engineFactory.createConnection();
            connection.setContainer(_name);
            c.setConnection(connection);
            //TODO: SSL and full SASL
            Sasl sasl = c.sasl();
            if (sasl != null)
            {
                sasl.server();
                sasl.setMechanisms(new String[]{"ANONYMOUS"});
                sasl.done(Sasl.SaslOutcome.PN_SASL_OK);
            }
            connection.open();
        }
        //process active connectors, handling opened & closed connections as needed
        for (Connector c = _driver.connector(); c != null; c = _driver.connector())
        {
            _logger.log(Level.FINE, "Processing active connector " + c);
            try
            {
                c.process();
            } catch (IOException e) {
                _logger.log(Level.SEVERE, "Error processing connection", e);
            }
            Connection connection = c.getConnection();

            if (connection.getLocalState() == EndpointState.UNINITIALIZED)
            {
                connection.open();
            }

            Delivery delivery = connection.getWorkHead();
            while (delivery != null)
            {
                if (delivery.getLink() instanceof Sender && delivery.isUpdated())
                {
                    delivery.disposition(delivery.getRemoteState());
                }
                //TODO: delivery.clear(); What's the equivalent in java?
                delivery = delivery.getWorkNext();
            }
            _outgoing.slide();

            for (Session session : new Sessions(connection, UNINIT, ANY))
            {
                session.open();
                _logger.log(Level.FINE, "Opened session " + session);
            }
            for (Link link : new Links(connection, UNINIT, ANY))
            {
                //TODO: the following is not correct; should only copy those properties that we understand
                link.setSource(link.getRemoteSource());
                link.setTarget(link.getRemoteTarget());
                link.open();
                _logger.log(Level.FINE, "Opened link " + link);
            }

            distributeCredit();

            for (Link link : new Links(connection, ACTIVE, CLOSED))
            {
                link.close();
            }
            for (Session session : new Sessions(connection, ACTIVE, CLOSED))
            {
                session.close();
            }
            if (connection.getRemoteState() == EndpointState.CLOSED)
            {
                if (connection.getLocalState() == EndpointState.ACTIVE)
                {
                    connection.close();
                }
                else if (connection.getLocalState() == EndpointState.CLOSED)
                {
                    c.close();
                }
            }

            if (c.isClosed())
            {
                reclaimCredit(connection);
                c.destroy();
            }
            else
            {
                try
                {
                    c.process();
                }
                catch (IOException e)
                {
                    _logger.log(Level.SEVERE, "Error processing connection", e);
                }
            }
        }
    }

    private void waitUntil(Predicate condition) throws TimeoutException
    {
        waitUntil(condition, _timeout);
    }

    private void waitUntil(Predicate condition, long timeout) throws TimeoutException
    {
        processAllConnectors();

        //wait until timeout expires or until test is true
        long deadline = timeout < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + timeout;

        boolean wait = deadline > System.currentTimeMillis();
        boolean first = true;
        boolean done = false;

        while (first || (!done && wait))
        {
            if (wait && !done && !first) {
                _driver.doWait(timeout < 0 ? 0 : deadline - System.currentTimeMillis());
            }
            processActive();
            wait = deadline > System.currentTimeMillis();
            done = done || condition.test();
            first = false;
        }
        if (!done)
        {
            throw new TimeoutException();
        }
    }

    private Connection lookup(String host, String service)
    {
        for (Connector c : _driver.connectors())
        {
            Connection connection = c.getConnection();
            if (host.equals(connection.getRemoteContainer()) || service.equals(connection.getContext()))
            {
                return connection;
            }
        }
        return null;
    }

    private void reclaimCredit(Connection connection)
    {
        for (Link link : new Links(connection, ANY, ANY))
        {
            if (link instanceof Receiver && link.getCredit() > 0)
            {
                reclaimCredit(link.getCredit());
            }
        }
    }

    private void reclaimCredit(int credit)
    {
        _credit += credit;
        _distributed -= credit;
    }

    private void distributeCredit()
    {
        int linkCt = 0;
        // @todo track the number of opened receive links
        for (Connector c : _driver.connectors())
        {
            Connection connection = c.getConnection();
            for (Link link : new Links(connection, ACTIVE, ANY))
            {
                if (link instanceof Receiver) linkCt++;
            }
        }

        if (linkCt == 0) return;

        if (_unlimitedCredit)
        {
            _credit = linkCt * _creditBatch;
        }

        int batch = (_credit < linkCt) ? 1 : (_credit/linkCt);
        for (Connector c : _driver.connectors())
        {
            Connection connection = c.getConnection();
            for (Link link : new Links(connection, ACTIVE, ANY))
            {
                if (link instanceof Receiver)
                {
                    int have = ((Receiver) link).getCredit();
                    if (have < batch)
                    {
                        int need = batch - have;
                        int amount = (_credit < need) ? _credit : need;
                        ((Receiver) link).flow(amount);
                        _distributed += amount;
                        _credit -= amount;
                        if (_credit == 0) return;
                    }
                }
            }
        }
    }

    private interface Predicate
    {
        boolean test();
    }

    private class SentSettled implements Predicate
    {
        public boolean test()
        {
            //are all sent messages settled?
            for (Connector c : _driver.connectors())
            {
                Connection connection = c.getConnection();
                for (Link link : new Links(connection, ACTIVE, ANY))
                {
                    if (link instanceof Sender)
                    {
                        if (link.getQueued() > 0)
                        {
                            return false;
                        }
                        //TODO: Sender.unsettled() not yet implemented, when it is change to the following
                        //if (checkSettled(link.unsettled())
                        //{
                        //    return false;
                        //}
                    }
                }
            }
            //TODO: Sender.unsettled() not yet implemented, when it is change to the following
            //return true;
            return checkSettled(_outgoing.deliveries());
        }

        boolean checkSettled(Iterator<Delivery> unsettled)
        {
            if (unsettled != null)
            {
                while (unsettled.hasNext())
                {
                    Delivery d = unsettled.next();
                    if (d == null)
                    {
                        break;
                    }
                    if (d.getRemoteState() != null || d.remotelySettled())
                    {
                        d.settle();
                    }
                    else if (d.getLink().getSession().getConnection().getRemoteState() == EndpointState.CLOSED)
                    {
                        continue;
                    }
                    else
                    {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    private class MessageAvailable implements Predicate
    {
        public boolean test()
        {
            //do we have at least one message?
            for (Connector c : _driver.connectors())
            {
                Connection connection = c.getConnection();
                Delivery delivery = connection.getWorkHead();
                while (delivery != null)
                {
                    if (delivery.isReadable() && !delivery.isPartial())
                    {
                        return true;
                    }
                    else
                    {
                        delivery = delivery.getWorkNext();
                    }
                }
            }
            return false;
        }
    }

    private class AllClosed implements Predicate
    {
        public boolean test()
        {
            if (_driver.connectors().iterator().hasNext()) return false;
            else return true;
        }

    }

    private final SentSettled _sentSettled = new SentSettled();
    private final MessageAvailable _messageAvailable = new MessageAvailable();
    private final AllClosed _allClosed = new AllClosed();

    private interface LinkFinder<C extends Link>
    {
        C test(Link link);
        C create(Session session);
    }

    private class SenderFinder implements LinkFinder<Sender>
    {
        private final String _path;

        SenderFinder(String path)
        {
            _path = path;
        }

        public Sender test(Link link)
        {
            if (link instanceof Sender && matchTarget((Target) link.getTarget(), _path))
            {
                return (Sender) link;
            }
            else
            {
                return null;
            }
        }

        public Sender create(Session session)
        {
            Sender sender = session.sender(_path);
            Target target = new Target();
            target.setAddress(_path);
            sender.setTarget(target);
            return sender;
        }
    }

    private class ReceiverFinder implements LinkFinder<Receiver>
    {
        private final String _path;

        ReceiverFinder(String path)
        {
            _path = path;
        }

        public Receiver test(Link link)
        {
            if (link instanceof Receiver && matchSource((Source) link.getSource(), _path))
            {
                return (Receiver) link;
            }
            else
            {
                return null;
            }
        }

        public Receiver create(Session session)
        {
            Receiver receiver = session.receiver(_path);
            Source source = new Source();
            source.setAddress(_path);
            receiver.setSource(source);
            return receiver;
        }
    }

    private <C extends Link> C getLink(String host, int port, LinkFinder<C> finder)
    {
        String service = host + ":" + port;
        Connection connection = lookup(host, service);
        if (connection == null)
        {
            Connector connector = _driver.createConnector(host, port, null);
            _logger.log(Level.FINE, "Connecting to " + host + ":" + port);
            connection = _engineFactory.createConnection();
            connection.setContainer(_name);
            connection.setHostname(host);
            connection.setContext(service);
            connector.setConnection(connection);
            Sasl sasl = connector.sasl();
            if (sasl != null)
            {
                sasl.client();
                sasl.setMechanisms(new String[]{"ANONYMOUS"});
            }
            connection.open();
        }

        for (Link link : new Links(connection, ACTIVE, ANY))
        {
            C result = finder.test(link);
            if (result != null) return result;
        }
        Session session = connection.session();
        session.open();
        C link = finder.create(session);
        link.open();
        return link;
    }

    private static class Links implements Iterable<Link>
    {
        private final Connection _connection;
        private final EnumSet<EndpointState> _local;
        private final EnumSet<EndpointState> _remote;

        Links(Connection connection, EnumSet<EndpointState> local, EnumSet<EndpointState> remote)
        {
            _connection = connection;
            _local = local;
            _remote = remote;
        }

        public java.util.Iterator<Link> iterator()
        {
            return new LinkIterator(_connection, _local, _remote);
        }
    }

    private static class LinkIterator implements java.util.Iterator<Link>
    {
        private final EnumSet<EndpointState> _local;
        private final EnumSet<EndpointState> _remote;
        private Link _next;

        LinkIterator(Connection connection, EnumSet<EndpointState> local, EnumSet<EndpointState> remote)
        {
            _local = local;
            _remote = remote;
            _next = connection.linkHead(_local, _remote);
        }

        public boolean hasNext()
        {
            return _next != null;
        }

        public Link next()
        {
            try
            {
                return _next;
            }
            finally
            {
                _next = _next.next(_local, _remote);
            }
        }

        public void remove()
        {
            throw new UnsupportedOperationException();
        }
    }

    private static class Sessions implements Iterable<Session>
    {
        private final Connection _connection;
        private final EnumSet<EndpointState> _local;
        private final EnumSet<EndpointState> _remote;

        Sessions(Connection connection, EnumSet<EndpointState> local, EnumSet<EndpointState> remote)
        {
            _connection = connection;
            _local = local;
            _remote = remote;
        }

        public java.util.Iterator<Session> iterator()
        {
            return new SessionIterator(_connection, _local, _remote);
        }
    }

    private static class SessionIterator implements java.util.Iterator<Session>
    {
        private final EnumSet<EndpointState> _local;
        private final EnumSet<EndpointState> _remote;
        private Session _next;

        SessionIterator(Connection connection, EnumSet<EndpointState> local, EnumSet<EndpointState> remote)
        {
            _local = local;
            _remote = remote;
            _next = connection.sessionHead(_local, _remote);
        }

        public boolean hasNext()
        {
            return _next != null;
        }

        public Session next()
        {
            try
            {
                return _next;
            }
            finally
            {
                _next = _next.next(_local, _remote);
            }
        }

        public void remove()
        {
            throw new UnsupportedOperationException();
        }
    }

    private void adjustReplyTo(Message m)
    {
        String original = m.getReplyTo();
        if (original == null || original.length() == 0)
        {
            m.setReplyTo("amqp://" + _name);
        }
        else if (original.startsWith("~/"))
        {
            m.setReplyTo("amqp://" + _name + "/" + original.substring(2));
        }
    }

    private static String cleanPath(String path)
    {
        //remove leading '/'
        if (path != null && path.length() > 0 && path.charAt(0) == '/')
        {
            return path.substring(1);
        }
        else
        {
            return path;
        }
    }

    private static boolean matchTarget(Target target, String path)
    {
        if (target == null) return path.isEmpty();
        else return path.equals(target.getAddress());
    }

    private static boolean matchSource(Source source, String path)
    {
        if (source == null) return path.isEmpty();
        else return path.equals(source.getAddress());
    }

    private static int defaultPort(String scheme)
    {
        if ("amqps".equals(scheme)) return 5671;
        else return 5672;
    }

    private static EngineFactory defaultEngineFactory()
    {
        return (EngineFactory) protonFactoryLoader.loadFactory(EngineFactory.class);
    }

    private static DriverFactory defaultDriverFactory()
    {
        return (DriverFactory) protonFactoryLoader.loadFactory(DriverFactory.class);
    }

    private static MessageFactory defaultMessageFactory()
    {
        return (MessageFactory) protonFactoryLoader.loadFactory(MessageFactory.class);
    }

}