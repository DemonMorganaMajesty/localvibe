package com.localvibe.controller;


import com.localvibe.dto.Result;
import com.localvibe.entity.ShopType;
import com.localvibe.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.List;


@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        List<ShopType> typeList =new ArrayList<>();
        //直接调用mybatis-plus sql语句查询 数据没有保存到缓存
        //typeList=typeService.query().orderByAsc("sort").list();

        //使用三种不同类型的value 数据保存到redis缓存
        //typeList=typeService.selectByRedisString();
        //typeList=typeService.selectByRedisList();
        typeList=typeService.selectByRedisHash();

        if(typeList==null||typeList.isEmpty())
            return Result.fail("数据不存在,查询失败");

        return Result.success(typeList);
    }
}
