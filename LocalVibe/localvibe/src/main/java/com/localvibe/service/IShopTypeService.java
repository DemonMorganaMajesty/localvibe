package com.localvibe.service;

import com.localvibe.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;


public interface IShopTypeService extends IService<ShopType> {
    // redis存储的数据类型是 string
    public List<ShopType>selectByRedisString();
    public List<ShopType>selectByRedisList();
    public List<ShopType>selectByRedisHash();
}
