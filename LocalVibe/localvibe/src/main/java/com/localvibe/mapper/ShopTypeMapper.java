package com.localvibe.mapper;

import com.localvibe.entity.Shop;
import com.localvibe.entity.ShopType;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ShopTypeMapper extends BaseMapper<ShopType> {
    @Select("select * from tb_shop_type")
    public List<ShopType> selectByRedis();
}
