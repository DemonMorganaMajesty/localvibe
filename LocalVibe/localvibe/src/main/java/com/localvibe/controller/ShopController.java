package com.localvibe.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localvibe.dto.Result;
import com.localvibe.entity.Shop;
import com.localvibe.service.IShopService;
import com.localvibe.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;


@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        // 改造：selectById 内部已返回 Result(多级缓存封装)，这里直接透传，避免二次包装导致前端取不到 data
        return shopService.selectById(id);
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        // 写入数据库
        shopService.save(shop);
        // 返回店铺id
        return Result.success(shop.getId());
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        // 写入数据库
        return shopService.updateShop(shop);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param
     * @return 商铺列表
     */
    //分页+店铺类型查询：默认按热度；传入 distanceRange 后按 Redis GEO 距离筛选
    @GetMapping("/of/type")
    public Result queryShopByTypeId(
            //required=false 不是强制需要参数 x,y 所以
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "pageNumber", defaultValue = "1") Integer pageNumber,
            @RequestParam(value = "x",required = false) Double x,
            @RequestParam(value = "y",required = false) Double y,
            @RequestParam(value = "distanceRange", required = false) String distanceRange
            ) {
        return shopService.queryShopByType(typeId,pageNumber,x,y,distanceRange);
    }

    // 热门门店：按销量降序返回前5家(首页"热门门店"一排展示)
    @GetMapping("/hot")
    public Result queryHotShops() {
        Page<Shop> page = shopService.query()
                .orderByDesc("sold")
                .page(new Page<>(1, 5));
        return Result.success(page.getRecords());
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.success(page.getRecords());
    }
}
