package com.localvibe.mapper;

import com.localvibe.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

import static net.sf.jsqlparser.util.validation.metadata.NamedObject.user;


public interface UserMapper extends BaseMapper<User> {
    //根据手机号查询用户
    @Select("select * from tb_user where phone=#{phone} ")
    public User selectUserByPhone(String phone);

    //根据手机号插入用户 nickName不为空
    @Insert("insert into tb_user(phone,nick_name,update_time,create_time) " +
            "values(#{phone},#{nickName},#{now},#{now}) ")
    public void insertByPhone(String phone, String nickName, LocalDateTime now);

    @Select("select * from tb_user where phone=#{loginPhone} and password=#{loginPassword}")
    public User selectUserByPhoneAndPassword(String loginPhone, String loginPassword);
}
