package com.example.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.util.List;

@Component
public class CanalListener {

    @Value("${canal.server}")
    private String canalServer;

    @Value("${canal.destination}")
    private String destination;

    private final StringRedisTemplate stringRedisTemplate;
    private final RestTemplate restTemplate;

    private CanalConnector connector;
    private volatile boolean running = true;

    public CanalListener(StringRedisTemplate stringRedisTemplate, RestTemplate restTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    public void init() {
        new Thread(() -> {
            while (running) {
                try {
                    String[] hostPort = canalServer.split(":");
                    String host = hostPort[0];
                    int port = Integer.parseInt(hostPort[1]);

                    connector = CanalConnectors.newSingleConnector(
                            new InetSocketAddress(host, port),
                            destination,
                            "",
                            ""
                    );
                    connector.connect();
                    connector.subscribe();
                    System.out.println("====Canal客户端连接成功====");

                    while (running) {
                        Message message = connector.getWithoutAck(100);
                        long batchId = message.getId();
                        int size = message.getEntries().size();

                        // 删掉这里：System.out.println("canal收到entries数量：" + size);

                        if (batchId == -1 || size == 0) {
                            Thread.sleep(200);
                            continue;
                        }
                        // 只有有变更事件才打印
                        System.out.println("canal收到entries数量：" + size);
                        handleEntries(message.getEntries());
                        connector.ack(batchId);
                    }
                } catch (Exception e) {
                    System.err.println("Canal客户端异常，5s后重连");
                    e.printStackTrace();
                    if (connector != null) {
                        connector.disconnect();
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "canal‑consume‑thread").start();
    }

    private void handleEntries(List<CanalEntry.Entry> entries) {
        for (CanalEntry.Entry entry : entries) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }
            CanalEntry.RowChange rowChange;
            try {
                rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            } catch (Exception e) {
                throw new RuntimeException("parse error", e);
            }

            String schema = entry.getHeader().getSchemaName();
            String table = entry.getHeader().getTableName();
            System.out.printf("schema:%s , table:%s%n", schema, table);

            if (!"test".equals(schema) || !"test".equals(table)) {
                System.out.println("过滤掉，不是目标表");
                continue;
            }

            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                CanalEntry.EventType eventType = rowChange.getEventType();
                if (!(eventType == CanalEntry.EventType.UPDATE
                        || eventType == CanalEntry.EventType.INSERT
                        || eventType == CanalEntry.EventType.DELETE)) {
                    continue;
                }

                Integer id = null;
                // 更新/删除从before取id；新增从after取id
                List<CanalEntry.Column> columnList;
                if (eventType == CanalEntry.EventType.DELETE) {
                    columnList = rowData.getBeforeColumnsList();
                } else {
                    columnList = rowData.getAfterColumnsList();
                }

                for (CanalEntry.Column col : columnList) {
                    if ("id".equals(col.getName())) {
                        id = Integer.parseInt(col.getValue());
                        break;
                    }
                }
                if (id == null) {
                    continue;
                }
                System.out.println("【Canal监听到test.test变更】id=" + id + " ,事件类型:" + eventType);

                // 删除Redis缓存
                stringRedisTemplate.delete("item:" + id);

                // 可选：调用OpenResty接口，清除Nginx共享字典缓存
                // restTemplate.getForObject("http://172.24.116.171:8085/cache/remove?id="+id,String.class);
            }
        }
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (connector != null) {
            connector.disconnect();
        }
    }
}