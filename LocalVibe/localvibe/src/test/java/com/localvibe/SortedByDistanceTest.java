package com.localvibe;

import com.localvibe.entity.Shop;
import com.localvibe.service.IShopService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.localvibe.utils.RedisConstants.GEOGRAPHY_SHOPTYPE_KEY;

/*做数据导入 商铺的信息是管理人员添加的,一般不会变,所以最好直接作为一个测试
导入附近的商铺信息(按照距离和店铺的类型)
 */
@SpringBootTest
public class SortedByDistanceTest {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IShopService shopService;

    @Test
    public void testLoadShopDataSortedByDistance(){
        //查询店铺信息,数据大的时候循环分批查询,这里数据少直接一次查询
        List<Shop> list =shopService.list();

        //把查询到的店铺信息 根据类型typeId作为一个分组,一个类型一个集合
        Map<Long,List<Shop>> map=list.stream() //shop::getTypeId();
            .collect(Collectors.groupingBy(shop-> shop.getTypeId()));
     /* Map<Long,List<Shop>> map=new HashMap<>();
        for(Shop shop:list){
            List<Shop> listT = map.get(shop.getTypeId());
            if(listT==null || listT.isEmpty()){
                listT=new ArrayList<>();
                map.put(shop.getTypeId(),listT);
            }
            listT.add(shop);

            //map.getOrDefault(shop.getTypeId(),new ArrayList<>()).add(shop);
           1.若是为空的话 创建的新集合没有加入 map中
           2. getOrDefault 返回的是boolean 也不能用集合去接 然后放入map
        }*/

        //按照类型的分组 存入redis
        for(Map.Entry<Long,List<Shop>>entry: map.entrySet()){
            //获取店铺的类型 typeId  key
            Long shopTypeId=entry.getKey();
            //存入redis 的key
            String key= GEOGRAPHY_SHOPTYPE_KEY +shopTypeId;
            //获取 该类型下的所有的店铺的集合  shops
            List<Shop> shops =entry.getValue();
        /*    //把shop的集合全部放入 redis 循环放入效率很低
            for(Shop shop: shops){
               stringRedisTemplate.opsForGeo().
                       add(key,new Point(shop.getX(),shop.getY()),
                       shop.getTypeId().toString());
            }
            */

            /*把所有店铺的坐标封装为Location集合,一起存入redis
            Location 两个成员变量 name Point(x,y)
            为什么要指定集合的大小,不指定默认是16 装满了会自动的扩容,消耗大
              */
            List<RedisGeoCommands.GeoLocation<String>> locations=
                    new ArrayList<>(shops.size());
            //循环进行类型的转化 并且存入locations
            for(Shop shop:shops){
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())));
            }
            //只向redis  写入一次数据 减少访问的次数
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }

}
