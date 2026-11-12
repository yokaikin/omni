package com.omni.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.omni.notification.entity.MqDeadLetter;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MqDeadLetterMapper extends BaseMapper<MqDeadLetter> {
}
