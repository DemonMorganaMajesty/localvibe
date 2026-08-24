package com.localvibe.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localvibe.dto.Result;
import com.localvibe.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;
import com.localvibe.utils.SystemConstants;


public interface IShopService extends IService<Shop> {
    //分页+店铺类型+排序/距离查询，默认按热度，选择距离后才使用 Redis GEO
    Result queryShopByType(Integer typeId, Integer pageNumber, Double x, Double y, String distanceRange);

    //根据ID 查询
    Result selectById(Long id);

    //有缓存穿透改怎么的查询代码
    Result selectByIdWithCacheThrough(Long id);

    //根据ID修改信息
    Result updateShop(Shop shop);

}
