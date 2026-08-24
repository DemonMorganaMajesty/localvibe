package com.localvibe.service.impl;

import com.localvibe.entity.UserInfo;
import com.localvibe.mapper.UserInfoMapper;
import com.localvibe.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;


@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
