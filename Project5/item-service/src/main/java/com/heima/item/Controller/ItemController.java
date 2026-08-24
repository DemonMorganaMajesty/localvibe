package com.heima.item.Controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.heima.item.config.CacheSyncService;
import com.heima.item.pojo.Item;
import com.heima.item.pojo.ItemStock;
import com.heima.item.pojo.PageDTO;
import com.heima.item.service.IItemService;
import com.heima.item.service.IItemStockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

//最简单的跨域写法 也可以写成congif
@CrossOrigin(origins = "http://localhost:8084")
@RestController
@RequestMapping("item")
public class ItemController {

    @Autowired
    private IItemService itemService;
    @Autowired
    private IItemStockService itemStockService;
    @Autowired

    private Cache<Long,Item> itemCache;
    @Autowired
    private Cache<Long,ItemStock> itemStockCache;

    //也是实现给redis 发消息的接口 更简单 可以替换stringRedisTemplate
    @Autowired
    private CacheSyncService cacheSyncService;
    //新增：Redis消息发布
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("list")
    public PageDTO queryItemPage(
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "5") Integer size){
        // 分页查询商品
        Page<Item> result = itemService.query()
                .ne("status", 3)
                .page(new Page<>(page, size));

        // 查询库存
        List<Item> list = result.getRecords().stream().peek(item -> {
            ItemStock stock = itemStockService.getById(item.getId());
            item.setStock(stock.getStock());
            item.setSold(stock.getSold());
        }).collect(Collectors.toList());

        // 封装返回
        return new PageDTO(result.getTotal(), list);
    }

    @PostMapping
    public void saveItem(@RequestBody Item item){
        itemService.saveItem(item);
        System.out.println("【发布 Redis 消息】item.update, id=" + item.getId());
        System.out.println("【stringRedisTemplate】=" + stringRedisTemplate);
        //新增：发布消息通知其他实例更新缓存
        stringRedisTemplate.convertAndSend("item.insert", String.valueOf(item.getId()));
        System.out.println("【消息已发布】");
        //cacheSyncService.sync(item.getId(), "insert");
    }

    @PutMapping
    public void updateItem(@RequestBody Item item) {

        itemService.updateById(item);
        System.out.println("【发布 Redis 消息】item.update, id=" + item.getId());
        System.out.println("【stringRedisTemplate】=" + stringRedisTemplate);
        stringRedisTemplate.convertAndSend("item.update", String.valueOf(item.getId()));
        System.out.println("【消息已发布】");
        //cacheSyncService.sync(item.getId(), "update");
    }

    @DeleteMapping("/{id}")
    public void deleteItemById(@PathVariable("id") Long id){

        itemService.update().set("status", 3).eq("id", id).update();
        System.out.println("【发布 Redis 消息】item.update, id=" +id);
        System.out.println("【stringRedisTemplate】=" + stringRedisTemplate);
        //新增：发布消息通知其他实例删除缓存
        stringRedisTemplate.convertAndSend("item.delete", String.valueOf(id));
        System.out.println("【消息已发布】");
        // cacheSyncService.sync(id, "delete");
    }

    //库存只有更新操作 没有删除和保存的操作
    @PutMapping("stock")
    public void updateStock(@RequestBody ItemStock itemStock){
        itemStockService.updateById(itemStock);
    }

    @GetMapping("/{id}")
    public Item findItemById(@PathVariable("id") Long id){
        //补充信息
        completeItem(itemService.getById(id));

        //get:存在直接返回 不存在那么就去调用数据库/函数 查询
        return itemCache.get(id,key->{
            System.out.println("【缓存未命中】正在查询数据库，id=" + key);
            return itemService.query()
                    .ne("status", 3).eq("id", id)
                    .one();}
        );
     /* 不使用jvm进程缓存 直接查询数据库
      return itemService.query()
                .ne("status", 3).eq("id", id)
                .one();*/
    }

    @GetMapping("/stock/{id}")
    public ItemStock findStockById(@PathVariable("id") Long id){
        //get:存在直接返回 不存在那么就去调用数据库/函数 查询
        return itemStockCache.get(id,key->
        {  System.out.println("【缓存未命中】正在查询数据库，id=" + key);
            return itemStockService.getById(id);
        });

        // return stockService.getById(id);
    }

    //补充商品的库存和已经售卖的信息
    public void completeItem(Item item){
        Long id=item.getId();
        ItemStock stock=itemStockService.getById(id);
        item.setStock(stock.getStock());
        item.setSold(stock.getSold());
    }
}