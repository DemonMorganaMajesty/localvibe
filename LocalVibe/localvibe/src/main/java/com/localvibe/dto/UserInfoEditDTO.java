package com.localvibe.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 资料编辑 DTO：昵称/头像(tb_user) + 介绍/城市/性别/生日(tb_user_info)
 */
@Data
public class UserInfoEditDTO {
    /**
     * 昵称
     */
    private String nickName;
    /**
     * 头像路径
     */
    private String icon;
    /**
     * 个人介绍
     */
    private String introduce;
    /**
     * 城市
     */
    private String city;
    /**
     * 性别
     */
    private Boolean gender;
    /**
     * 生日
     */
    private LocalDate birthday;
}