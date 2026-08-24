package com.localvibe.mapper;

import com.localvibe.entity.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ShopMapper extends BaseMapper<Shop> {

    @Select("select * from tb_shop where id=#{id} ")
    public Shop selectById(Long id);

}
