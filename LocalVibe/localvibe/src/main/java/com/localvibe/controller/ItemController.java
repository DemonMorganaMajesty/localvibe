package com.localvibe.controller;

import com.localvibe.dto.Result;
import com.localvibe.entity.Item;
import com.localvibe.service.IItemService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 * 商品前端控制器（openresty 多级缓存链路的数据源）
 * </p>
 *
 * 说明：/item/{id} 与 /item/stock/{id} 返回的是原始商品 JSON（非 Result 包装），
 * 以便 WSL openresty 的 item.lua 直接解析并合并 stock 字段。
 * 链路：nginx:81 -> openresty:8085(lua) -> Redis -> 本控制器 -> Caffeine/MySQL
 *
 * @author 改造新增
 */
@RestController
@RequestMapping("/item")
public class ItemController {

    @Resource
    private IItemService itemService;

    /**
     * 多级缓存查询商品（openresty lua 内部代理路径 /path/item/{id} 对应此处）
     *
     * @param id 商品id
     * @return 商品原始 JSON
     */
    @GetMapping("/{id}")
    public Item queryItemById(@PathVariable("id") Long id) {
        Result result = itemService.queryItemById(id);
        return result.getSuccess() ? (Item) result.getData() : null;
    }

    /**
     * 多级缓存查询商品库存（openresty lua 内部代理路径 /path/item/stock/{id} 对应此处）
     *
     * @param id 商品id
     * @return 库存原始 JSON
     */
    @GetMapping("/stock/{id}")
    public Object queryItemStockById(@PathVariable("id") Long id) {
        Result result = itemService.queryItemStockById(id);
        return result.getSuccess() ? result.getData() : null;
    }

    /**
     * 更新商品（用于后台管理/测试多级缓存失效，postman 模拟）
     *
     * @param item 商品数据
     * @return 更新结果
     */
    @PutMapping
    public Result updateItem(@RequestBody Item item) {
        return itemService.updateItem(item);
    }
}
