package com.localvibe.utils;

import com.localvibe.dto.UserDTO;
import com.localvibe.entity.User;

//threadlocal 线程 为每个请求session的用户分配
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO userDto){
        tl.set(userDto);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
