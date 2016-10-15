package com.tongbanjie.tevent.client;

import com.tongbanjie.tevent.client.processer.ServerRequestProcessor;
import com.tongbanjie.tevent.client.sender.MQMessageSender;
import com.tongbanjie.tevent.common.util.NamedSingleThreadFactory;
import com.tongbanjie.tevent.common.util.NamedThreadFactory;
import com.tongbanjie.tevent.registry.RecoverableRegistry;
import com.tongbanjie.tevent.registry.zookeeper.ClientZooKeeperRegistry;
import com.tongbanjie.tevent.rpc.RpcClient;
import com.tongbanjie.tevent.rpc.netty.NettyClientConfig;
import com.tongbanjie.tevent.rpc.netty.NettyRpcClient;
import com.tongbanjie.tevent.rpc.protocol.RequestCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * 〈一句话功能简述〉<p>
 * 〈功能详细描述〉
 *
 * @author zixiao
 * @date 16/10/13
 */
public class ClientController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientController.class);

    /********************** 配置 ***********************/

    private final ClientConfig clientConfig;

    private final NettyClientConfig nettyClientConfig;

    private final ConcurrentHashMap<String/* group */, MQMessageSender> messageSenderTable = new ConcurrentHashMap<String,MQMessageSender>();

    private final ConcurrentHashMap<Integer/* brokerId */, String/* address */> serverAddressTable = new ConcurrentHashMap<Integer, String>();


    /********************** client manager ***********************/
    // 服务端连接管理
    private ServerManager serverManager;

    /********************** 服务 ***********************/
    //服务注册
    private final RecoverableRegistry clientRegistry;

    //远程通信层对象
    private RpcClient rpcClient;

    /********************** 线程池 ***********************/
    // 处理发送消息线程池
    private ExecutorService sendMessageExecutor;

    // 对消息写入进行流控
    private final BlockingQueue<Runnable> sendThreadPoolQueue;

    //客户端定时线程
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
            new NamedSingleThreadFactory("ClientScheduledThread"));

    public ClientController(ClientConfig clientConfig, NettyClientConfig nettyClientConfig) {
        this.clientConfig = clientConfig;
        this.nettyClientConfig = nettyClientConfig;

        this.sendThreadPoolQueue = new LinkedBlockingQueue<Runnable>(this.clientConfig.getSendThreadPoolQueueCapacity());
        this.clientRegistry = new ClientZooKeeperRegistry(this.clientConfig.getRegistryAddress());
    }

    public boolean initialize() {
        boolean result = true;

        if (result) {
            this.rpcClient = new NettyRpcClient(this.nettyClientConfig);

            this.serverManager = new ServerManager(this);

            this.sendMessageExecutor = new ThreadPoolExecutor(//
                    this.clientConfig.getSendMessageThreadPoolNums(),//
                    this.clientConfig.getSendMessageThreadPoolNums(),//
                    1000 * 60,//
                    TimeUnit.MILLISECONDS,//
                    this.sendThreadPoolQueue,//
                    new NamedThreadFactory("SendMessageThread_"));

            this.registerProcessor();
        }

        return result;
    }

    public void registerProcessor() {
        ServerRequestProcessor serverRequestProcessor = new ServerRequestProcessor(this);
        this.rpcClient.registerProcessor(RequestCode.CHECK_TRANSACTION_STATE, serverRequestProcessor, this.sendMessageExecutor);
    }

    public void start() throws Exception {

        //rpcClient
        if (this.rpcClient != null) {
            this.rpcClient.start();
        }

        //定时向所有服务端发送心跳
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    ClientController.this.serverManager.sendHeartbeatToAllServer();
                } catch (Exception e) {
                    LOGGER.error("ScheduledTask sendHeartbeatToAllServer exception", e);
                }
            }
        }, 5 * 1000, clientConfig.getHeartbeatInterval(), TimeUnit.MILLISECONDS);

        //start Registry
        try {
            clientRegistry.start();
        } catch (Exception e) {
            LOGGER.error("The registry connect failed, address: " + clientConfig.getRegistryAddress(), e);
        }
    }

    public void shutdown() {
        if (this.rpcClient != null) {
            this.rpcClient.shutdown();
        }

        if (this.sendMessageExecutor != null) {
            this.sendMessageExecutor.shutdown();
        }
    }

    public NettyClientConfig getNettyClientConfig() {
        return nettyClientConfig;
    }

    public ClientConfig getClientConfig() {
        return clientConfig;
    }

    public RpcClient getRpcClient() {
        return rpcClient;
    }

    public RecoverableRegistry getClientRegistry() {
        return clientRegistry;
    }
}
