package com.localvibe.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.localvibe.dto.Result;
import com.localvibe.entity.Item;

/**
 * <p>
 * 商品服务接口（多级缓存：Caffeine L1 -> Redis L2 -> MySQL L3）
 * </p>
 *
 * @author 改造新增
 */
public interface IItemService extends IService<Item> {

    /**
     * 多级缓存查询商品
     *
     * @param id 商品id
     * @return 商品数据
     */
    Result queryItemById(Long id);

    /**
     * 多级缓存查询商品库存
     *
     * @param id 商品id
     * @return 库存数据
     */
    Result queryItemStockById(Long id);

    /**
     * 更新商品（先更新数据库，再失效各级缓存并记录变更日志兜底）
     *
     * @param item 商品数据
     * @return 更新结果
     */
    Result updateItem(Item item);
}
