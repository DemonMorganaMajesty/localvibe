package com.heima.item.config;


import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.item.pojo.Item;
import com.heima.item.pojo.ItemStock;
import com.heima.item.service.IItemService;
import com.heima.item.service.IItemStockService;
import com.heima.item.service.impl.ItemService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisHandler implements InitializingBean {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    //注入的是接口的实现类对象
    @Autowired
    private IItemService itemService;
    @Autowired
    private IItemStockService stockService;

    //json序列化工具(以前用是 hutool需要导入依赖) 这是spring里带的工具
    private static final ObjectMapper OBJECT_MAPPER=new ObjectMapper();

    /*初始化缓存(缓存预热,避免冷启动,大量的数据直接查询数据库)
     InitializingBean的函数在bean创建完autoWired注入后,自动执行这个函数
     从而实现初始化的问题
     */
    @Override
    public void afterPropertiesSet() throws Exception {
    /*查询商品的信息,库存的信息 缓存分离(大数据的情况下只需要查热点的数据)
      这里数据不多,预热处理全部放入缓存
      plus 查询
     */
        List<Item> itemList = itemService.list();
        List<ItemStock> stockList = stockService.list();

        //商品遍历放入缓存 redis存数据的格式,一个类的成员数据最好使用json
        for(Item item: itemList){
            String itemJson = OBJECT_MAPPER.
                    writeValueAsString(item);
            String key="item:id:"+item.getId();
            stringRedisTemplate.opsForValue().set(key,itemJson);
        }
        //商品库存遍历放入缓存 redis存数据的格式,一个类的成员数据最好使用json
        for(ItemStock stock: stockList){
            String stockJson = OBJECT_MAPPER.
                    writeValueAsString(stock);
            String key="stock:id:"+stock.getId();
            stringRedisTemplate.opsForValue().set(key,stockJson);
        }
    }
    // 保存
    public void saveItem (Item item){
        stringRedisTemplate.opsForValue ()
                .set ("item:id:"+item.getId (), JSONUtil.toJsonStr (item));

    }

// 删除
    public void deleteItem (Long id){
        stringRedisTemplate.delete ("item:id:"+id);
    }
}

