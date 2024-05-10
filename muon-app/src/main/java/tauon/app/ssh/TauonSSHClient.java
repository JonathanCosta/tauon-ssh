package tauon.app.ssh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tauon.app.exceptions.OperationCancelledException;
import tauon.app.services.SessionService;
import tauon.app.services.SettingsService;
import tauon.app.settings.PortForwardingRule;
import net.schmizz.keepalive.KeepAliveProvider;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder;
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive;
import net.schmizz.sshj.userauth.method.AuthNone;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import tauon.app.settings.SessionInfo;
import tauon.app.settings.HopEntry;
import tauon.app.ui.containers.main.GraphicalHostKeyVerifier;
import tauon.app.util.misc.PlatformUtils;
import tauon.app.util.ssh.SshUtil;
import tauon.app.util.misc.ExceptionUtils;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class TauonSSHClient {
    
    private static class UserPassCache{
        public char[] password;
        String user;
    }
    
    private static class PortForwardingState{
        PortForwardingRule rule;
        ServerSocket serverSocket;
        Thread thread;
    }
    
    private static final Logger LOG = LoggerFactory.getLogger(TauonSSHClient.class);
    
    private final SessionInfo info;
    private final GuiHandle<TauonSSHClient> guiHandle;
    private final PasswordFinder passwordFinder;
    private final ExecutorService executor;
    private final boolean openPortForwarding;
    
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    private SSHConnectedHop sshConnectedHop;
    private SFTPClient sftp;
    
    private GraphicalHostKeyVerifier hostKeyVerifier;
    
    private final List<UserPassCache> cache = new ArrayList<>();
    private final List<PortForwardingState> portForwardingStates = new ArrayList<>();
    
    public TauonSSHClient(
            SessionInfo info, GuiHandle<TauonSSHClient> guiHandle, PasswordFinder passwordFinder, ExecutorService executor, boolean openPortForwarding, GraphicalHostKeyVerifier hostKeyVerifier
    ) {
        this.hostKeyVerifier = hostKeyVerifier;
        this.info = info;
        this.guiHandle = guiHandle;
        this.passwordFinder = passwordFinder;
        this.executor = executor;
        this.openPortForwarding = openPortForwarding;
    }
    
    public synchronized boolean connect() throws InterruptedException {
        
        if(isConnected()) {
            LOG.warn("Client is already connected.");
            return true;
        }
        
        LOG.info("Begin connecting client.");
        
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Future<Boolean>> future = new AtomicReference<>();
        
        GuiHandle.BlockHandle blockHandle = guiHandle.blockUi(this, blockHandle2 -> {
            if(!cancelled.getAndSet(true)){
                Future<Boolean> f = future.get();
                if(f != null)
                    f.cancel(true);
            }
        });
        
        future.set(executor.submit(() -> {
            if(cancelled.get())
                return false;
            
            sftp = null;
            portForwardingStates.clear();
            
            try {
                sshConnectedHop = new SSHConnectedHop(info);
                sshConnectedHop.connect(0);
                
                if(openPortForwarding) {
                    
                    for (PortForwardingRule r : info.getPortForwardingRules()) {
                        if (r.getType() == PortForwardingRule.PortForwardingType.Local) {
                            try {
                                forwardLocalPort(r, sshConnectedHop.sshj);
                            } catch (Exception e) {
                                LOG.error("Local port forwarding failed: {}", r, e);
                            }
                        } else if (r.getType() == PortForwardingRule.PortForwardingType.Remote) {
                            try {
                                forwardRemotePort(r, sshConnectedHop.sshj);
                            } catch (Exception e) {
                                LOG.error("Remote port forwarding failed: {}", r, e);
                            }
                        }
                    }
                    
                }
                
                return true;
            }catch (Exception e){
                e.printStackTrace();
                sshConnectedHop.disconnect();
                return false;
            }
        }));
        
        try {
            return future.get().get();
        }catch (InterruptedException e){
            future.get().cancel(true);
            try {
                // Wait until is cancelled
                future.get().get();
            }catch (Exception ignored){
            
            }
            
            Thread.currentThread().interrupt();
            throw e;
        }catch (ExecutionException e){
            guiHandle.reportException(e.getCause());
            return false;
        }finally {
            blockHandle.unblock();
        }
        
    }
    
    private void disconnectPortForwardingStates() throws IOException, InterruptedException {
        
        try {
            for (PortForwardingState pf : portForwardingStates) {
                if (!pf.thread.isAlive())
                    continue;
                if (pf.serverSocket != null) {
                    pf.serverSocket.close();
                }
                pf.thread.interrupt();
            }
            
            for (PortForwardingState pf : portForwardingStates) {
                if (!pf.thread.isAlive())
                    continue;
                pf.thread.join();
            }
            
            portForwardingStates.clear();
        }finally {
            portForwardingStates.clear();
        }
    }
    
    public synchronized boolean isConnected(){
        return sshConnectedHop != null && sshConnectedHop.sshj.isConnected();
    }
    
    public synchronized void close() throws IOException, InterruptedException {
        
        if(!closed.getAndSet(true))
            return;
        
        if(sshConnectedHop != null) {
            sshConnectedHop.disconnect();
            sshConnectedHop = null;
        }
        
    }
    
    public synchronized SFTPClient getSftpClient() throws IOException {
        if (closed.get()) {
            throw new IOException("Closed by user");
        }
        
        if(!isConnected()){
            throw new IOException("Not connected");
        }
        
        if(sftp == null){
            sftp = sshConnectedHop.sshj.newSFTPClient();
            this.sftp.getSFTPEngine().setTimeoutMs(sshConnectedHop.sshj.getTimeout());
            this.sftp.getSFTPEngine().getSubsystem().setAutoExpand(true);
        }
        
        return sftp;
    }
    
    public synchronized Session openSession() throws Exception {
        if (closed.get()) {
            throw new IOException("Closed by user");
        }
        
        if(!isConnected()){
            throw new IOException("Not connected");
        }
        
        Session session = sshConnectedHop.sshj.startSession();
        
        if(info.isXForwardingEnabled()) {
            
            Random r = new Random();
            StringBuilder faker = new StringBuilder();
            for (int i = 0; i < 32; i++) {
                int n = r.nextInt(16);
                if(n < 10)
                    faker.append(n);
                else
                    faker.append((char)('a' + (char)(n-10)));
            }
            
            session.reqX11Forwarding("MIT-MAGIC-COOKIE-1", faker.toString(), 0);
        }
        
        return session;
    }
    
    private void forwardLocalPort(PortForwardingRule r, SSHClient ssh) throws Exception {
        PortForwardingState portForwardingState = new PortForwardingState();
        portForwardingState.rule = r;
        portForwardingStates.add(portForwardingState);
        
        portForwardingState.serverSocket = new ServerSocket();
        portForwardingState.serverSocket.setReuseAddress(true);
        portForwardingState.serverSocket.bind(new InetSocketAddress(r.getLocalHost(), r.getRemotePort()));
        
        portForwardingState.thread = new Thread(() -> {
            try {
                ssh.newLocalPortForwarder(
                                new Parameters(r.getLocalHost(), r.getLocalPort(), r.getRemoteHost(), r.getRemotePort()), portForwardingState.serverSocket)
                        .listen();
            } catch (IOException e) {
                portForwardingState.thread = null;
                guiHandle.reportPortForwardingFailed(r, e);
            } finally {
                portForwardingState.thread = null;
            }
        });
        
        portForwardingState.thread.start();
        
    }
    
    private void forwardRemotePort(PortForwardingRule r, SSHClient ssh) {
        PortForwardingState portForwardingState = new PortForwardingState();
        portForwardingState.rule = r;
        portForwardingStates.add(portForwardingState);
        
        portForwardingState.thread = new Thread(() -> {
            
            /*
             * We make _server_ listen on port 8080, which forwards all connections to us as
             * a channel, and we further forward all such channels to google.com:80
             */
            try {
                ssh.getRemotePortForwarder().bind(
                        // where the server should listen
                        new RemotePortForwarder.Forward(r.getRemoteHost(), r.getRemotePort()),
                        // what we do with incoming connections that are forwarded to us
                        new SocketForwardingConnectListener(new InetSocketAddress(r.getLocalHost(), r.getLocalPort())));
                
                // Something to hang on to so that the forwarding stays
                ssh.getTransport().join();
            } catch (ConnectionException | TransportException e) {
                portForwardingState.thread = null;
                guiHandle.reportPortForwardingFailed(r, e);
            } finally {
                portForwardingState.thread = null;
            }
            
        });
        
        portForwardingState.thread.start();
        
    }
    
    private class SSHConnectedHop {
        
        private SSHConnectedHop previousHop;
        private SSHClient sshj;
        
        // For connecting through tcp forwarding
        private ServerSocket serverSocket;
        private Thread thread;
        
        private final HopEntry hopEntry;
        
        public SSHConnectedHop(HopEntry hopEntry){
            this.hopEntry = hopEntry;
        }
        
        private void connect(int index) throws IOException, OperationCancelledException {
            
            try {
                
                ///////
                // CONNECT
                ///////
                
                DefaultConfig defaultConfig = new DefaultConfig();
                if (SettingsService.getSettings().isShowMessagePrompt()) {
                    System.out.println("enabled KeepAliveProvider");
                    defaultConfig.setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE);
                }
                this.sshj = new SSHClient(defaultConfig);
                
                if(info.isXForwardingEnabled()){
                    
                    if (PlatformUtils.IS_LINUX) {
                        sshj.registerX11Forwarder(new tauon.app.ssh.SocketForwardingConnectListener(
                                SshUtil.socketAddress("/tmp/.X11-unix/X0")
                        ));
                    } else {
                        
                        sshj.registerX11Forwarder(new SocketForwardingConnectListener(
//                        UnixDomainSocketAddress.of("/tmp/.X11-unix/X0")
                                new InetSocketAddress("localhost", 6000)
                        ));
                        
                    }
                    
                }
                
                this.sshj.setConnectTimeout(SettingsService.getSettings().getConnectionTimeout() * 1000);
                this.sshj.setTimeout(SettingsService.getSettings().getConnectionTimeout() * 1000);
                if (info.getJumpHosts().isEmpty() || index >= info.getJumpHosts().size()) {
                    this.setupProxyAndSocketFactory();
                    this.sshj.addHostKeyVerifier(hostKeyVerifier);
                    this.sshj.connect(hopEntry.getHost(), hopEntry.getPort());
                } else {
                    
                    try {
                        LOG.debug("Tunneling through...");
                        tunnelThrough(index);
                        LOG.debug("adding host key verifier");
                        this.sshj.addHostKeyVerifier(hostKeyVerifier);
                        LOG.debug("Host key verifier added");
                        if (info.getJumpType() == SessionInfo.JumpType.TcpForwarding) {
                            LOG.debug("tcp forwarding...");
                            this.connectViaTcpForwarding();
                        } else {
                            LOG.debug("port forwarding...");
                            this.connectViaPortForwarding();
                        }
                    } catch (Exception e) {
                        LOG.error("Exception while connecting multihop", e);
//                        disconnect(); // Will be disconnected by TauonSSHClient
                        throw e;
                    }
                }
                
                sshj.getConnection().getKeepAlive().setKeepAliveInterval(5);
                
                if(Thread.interrupted()){
                    Thread.currentThread().interrupt();
                    throw new InterruptedException(); // Will be disconnected by TauonSSHClient
                }
                
                ///////
                // AUTHORIZE
                ///////
                
                // Connection established, now find out supported authentication
                // methods
                List<String> allowedMethodsIfNotAuthenticated = new ArrayList<>();
                
                AtomicBoolean rememberUser = new AtomicBoolean();
                boolean isUserRemembered = false;
                
                UserPassCache userPassCache;
                while (cache.size() <= index)
                    cache.add(new UserPassCache());
                userPassCache = cache.get(index);
                
                if(userPassCache.user == null || userPassCache.user.isBlank()) {
                    
                    if(hopEntry.getUser() != null && !hopEntry.getUser().isBlank()){
                        userPassCache.user = hopEntry.getUser();
                        isUserRemembered = true;
                    }else{
                        userPassCache.user = guiHandle.promptUser(hopEntry, rememberUser);
                    }
                }else{
                    isUserRemembered = true;
                }
                
                if (userPassCache.user == null || userPassCache.user.isEmpty()) {
                    throw new OperationCancelledException();
                }
                
                boolean authenticated = this.getAuthMethods(allowedMethodsIfNotAuthenticated, userPassCache.user, rememberUser);
                if (authenticated) {
                    return; // All right
                }
                
                // loop over servers preferred authentication methods in the same
                // order sent by server
                for (String authMethod : allowedMethodsIfNotAuthenticated) {
                    
                    if (Thread.interrupted()) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedException(); // Will be disconnected by TauonSSHClient
                    }
                    
                    LOG.debug("Trying auth method: {}", authMethod);
                    
                    switch (authMethod) {
                        case "publickey":
                            try {
                                this.authPublicKey(index, userPassCache.user);
                                if(rememberUser.get()){
                                    info.setUser(userPassCache.user);
                                    SessionService.getInstance().setPasswordsFrom(info);
                                }
                                return; // All right
                            } catch (OperationCancelledException e) {
                                disconnect();
                                throw e;
                            } catch (Exception e) {
                                LOG.debug("Error authenticating", e);
                            }
                            break;
                        
                        case "keyboard-interactive":
                            try {
                                sshj.auth(userPassCache.user, new AuthKeyboardInteractive(new SSHJInteractiveResponseProvider(guiHandle)));
                                return; // All right
                            } catch (Exception e) {
                                LOG.debug("Error authenticating", e);
                            }
                            break;
                        
                        case "password":
                            try {
                                this.authPassword(index, userPassCache.user, isUserRemembered, rememberUser.get());
                                return; // All right
                            } catch (OperationCancelledException e) {
                                disconnect();
                                throw e;
                            } catch (Exception e) {
                                LOG.debug("Error authenticating", e);
                            }
                            break;
                    }
                    
                }
                
                throw new IOException("Authentication failed");
                
            } catch (Exception e) {
                throw ExceptionUtils.sneakyThrow(e);
            }
            
        }
        
        private char[] promptPassword(String user, int index, AtomicBoolean rememberPassword, boolean isRetrying){
            
            UserPassCache userPassCache;
            
            while (cache.size() <= index)
                cache.add(new UserPassCache());
            
            userPassCache = cache.get(index);
            
            if(isRetrying)
                userPassCache.password = null;
            
            if(userPassCache.password == null || userPassCache.password.length == 0) {
                
                if(hopEntry.getPassword() != null && !hopEntry.getPassword().isBlank()){
                    userPassCache.password = hopEntry.getPassword().toCharArray();
                }else {
                    char[] password = guiHandle.promptPassword(hopEntry, user, rememberPassword, isRetrying);
                    userPassCache.password = password;
                    return password;
                }
            }
            
            return userPassCache.password;
            
        }
        
        private boolean getAuthMethods(List<String> allowedMethodsIfNotAuthenticated, String user, AtomicBoolean remember)
                throws OperationCancelledException {
            System.out.println("Trying to get allowed authentication methods...");
            try {
                sshj.auth(user, new AuthNone());
                return true;
            } catch (Exception e) {
                allowedMethodsIfNotAuthenticated.addAll(sshj.getUserAuth().getAllowedMethods());
                LOG.debug("List of allowed authentications: {}", allowedMethodsIfNotAuthenticated);
            }
            return false;
        }
        
        private void authPublicKey(int index, String user) throws Exception {
            KeyProvider provider = null;
            if (info.getPrivateKeyFile() != null && !info.getPrivateKeyFile().isEmpty()) {
                File keyFile = new File(info.getPrivateKeyFile());
                if (keyFile.exists()) {
                    provider = sshj.loadKeys(info.getPrivateKeyFile(), passwordFinder);
                    LOG.debug("Key provider: {}", provider);
                    LOG.debug("Key type: {}", provider.getType());
                }
            }
            
            if (provider == null) {
                throw new Exception("No suitable key providers");
            }
            
            sshj.authPublickey(user, provider);
        }
        
        private void authPassword(int index, String user, boolean isUserRemembered, boolean haveToRememberUser) throws Exception {
            
            AtomicBoolean rememberPassword = isUserRemembered || haveToRememberUser ? new AtomicBoolean() : null;
            
            // keep on trying with password
            boolean isRetrying = false;
            while (true) {
                
                char[] password = promptPassword(user, index, rememberPassword, isRetrying);
                
                if (password == null || password.length == 0) {
                    throw new OperationCancelledException();
                }
                
                isRetrying = true;
                
                try {
                    String svalue = String.valueOf(password);
                    sshj.authPassword(user, password); // provide
                    // password
                    // updater
                    // PasswordUpdateProvider
                    // net.schmizz.sshj.userauth.password.PasswordUpdateProvider
                    if (haveToRememberUser || rememberPassword != null && rememberPassword.get()) {
                        if(haveToRememberUser)
                            info.setUser(user);
                        if(rememberPassword != null && rememberPassword.get())
                            info.setPassword(svalue);
                        guiHandle.saveInfo(info);
                    }
                    
                    return;
                } catch (Exception e) {
                    LOG.debug("Error authenticating", e);
                }
            }
        }
        
        // recursively
        private void tunnelThrough(int index) throws Exception {
            HopEntry ent = info.getJumpHosts().get(index);
//            SessionInfo hopInfo = new SessionInfo();
//            assert ent != null;
//            hopInfo.setHost(ent.getHost());
//            hopInfo.setPort(ent.getPort());
//            hopInfo.setUser(ent.getUser());
//            hopInfo.setPassword(ent.getPassword());
//            hopInfo.setPrivateKeyFile(ent.getKeypath());
            previousHop = new SSHConnectedHop(ent);
            previousHop.connect(index+1);
        }
        
        private void connectViaTcpForwarding() throws Exception {
            this.sshj.connectVia(this.previousHop.sshj.newDirectConnection(
                    hopEntry.getHost(), hopEntry.getPort()), hopEntry.getHost(), hopEntry.getPort()
            );
        }
        
        private void connectViaPortForwarding() throws Exception {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = serverSocket.getLocalPort();
            
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            thread = new Thread(() -> {
                try {
                    this.previousHop.sshj
                            .newLocalPortForwarder(
                                    new Parameters("127.0.0.1", port, info.getHost(), info.getPort()), serverSocket)
                            .listen();
                } catch (IOException e) {
                    throw ExceptionUtils.sneakyThrow(e);
                }
            });
            thread.setUncaughtExceptionHandler((th, e) -> {
                thrown.set(e);
            });
            thread.start();
            
            // Wait until bound
            while (thread.isAlive() && serverSocket.isBound() && thrown.get() == null) {
                // Yes! busy-waiting
                Thread.sleep(100);
            }
            
            if(thrown.get() != null){
                throw new ExecutionException(thrown.get());
            }
            
            this.sshj.connect("127.0.0.1", port);
        }
        
        public void disconnect() throws IOException, InterruptedException {
            
            disconnectPortForwardingStates();
            
            try {
                if (sshj != null)
                    sshj.disconnect();
            } catch (Exception e) {
                LOG.error("Error while disconnecting", e);
            }
            try {
                if (previousHop != null)
                    previousHop.disconnect();
            } catch (Exception e) {
                LOG.error("Error while disconnecting", e);
            }
            try {
                if (this.serverSocket != null) {
                    this.serverSocket.close();
                    if(thread != null) {
                        thread.join(); // Try to join the thread after stopping serversocket
                    }
                }
            } catch (Exception e) {
                LOG.error("Error while disconnecting", e);
            }
        }
        
        private void setupProxyAndSocketFactory() {
            String proxyHost = info.getProxyHost();
            int proxyType = info.getProxyType();
            String proxyUser = info.getProxyUser();
            String proxyPass = info.getProxyPassword();
            int proxyPort = info.getProxyPort();
            
            Proxy.Type proxyType1 = Proxy.Type.DIRECT;
            
            if (proxyType == 1) {
                proxyType1 = Proxy.Type.HTTP;
            } else if (proxyType > 1) {
                proxyType1 = Proxy.Type.SOCKS;
            }
            
            sshj.setSocketFactory(new CustomSocketFactory(proxyHost, proxyPort, proxyUser, proxyPass, proxyType1));
        }
        
    }
    
}
