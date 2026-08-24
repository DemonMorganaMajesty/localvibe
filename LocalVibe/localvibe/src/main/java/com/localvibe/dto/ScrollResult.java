package com.localvibe.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    //定义查询结果返回值是泛型 实现通用化
    private List<?> list;
    //上次查询结果的minScore(score为时间戳)
    private Long minTime;
    //偏移值
    private Integer offset;
}
