package com.lmy.live.user.provider.service.impl;

import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Maps;
import com.lmy.live.user.constants.CacheAsyncDeleteCode;
import com.lmy.live.user.constants.UserProviderTopicNames;
import com.lmy.live.user.dto.UserCacheAsyncDeleteDTO;
import com.lmy.live.user.dto.UserDTO;
import com.lmy.live.user.provider.dao.mapper.IUserMapper;
import com.lmy.live.user.provider.dao.po.UserPO;
import com.lmy.live.user.provider.service.IUserService;
import jakarta.annotation.Resource;
import org.apache.rocketmq.client.producer.MQProducer;
import org.apache.rocketmq.common.message.Message;
import org.idea.lmy.live.framework.redis.starter.key.UserProviderCacheKeyBuilder;
import org.lmy.live.common.interfaces.utils.ConvertBeanUtils;
import org.lmy.live.common.interfaces.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class UserServiceImpl implements IUserService {

    private final static Logger logger= LoggerFactory.getLogger(UserServiceImpl.class);

    @Resource
    private IUserMapper userMapper;

    @Resource
    private RedisTemplate<String,UserDTO> redisTemplate;

    @Resource
    private UserProviderCacheKeyBuilder userProviderCacheKeyBuilder;
    @Resource
    private MQProducer mqProducer;
    @Override
    public UserDTO getByUserId(Long userId) {
        if(userId==null){
            return null;
        }
        String key= userProviderCacheKeyBuilder.buildUserInfoKey(userId);
        UserDTO userDTO = redisTemplate.opsForValue().get(key);
        if(userDTO!=null){
            return userDTO;
        }
        userDTO = ConvertBeanUtils.convert(userMapper.selectById(userId), UserDTO.class);
        if(userDTO!=null){
            redisTemplate.opsForValue().set(key,userDTO,30, TimeUnit.MINUTES);
        }
        return userDTO;
    }

    @Override
    public boolean updateUserInfo(UserDTO userDTO) {
        if (userDTO == null || userDTO.getUserId() == null) {
            return false;
        }
        int updateStatus = userMapper.updateById(ConvertBeanUtils.convert(userDTO, UserPO.class));
        if (updateStatus > -1) {
            String key = userProviderCacheKeyBuilder.buildUserInfoKey(userDTO.getUserId());
            redisTemplate.delete(key);
            UserCacheAsyncDeleteDTO userCacheAsyncDeleteDTO = new UserCacheAsyncDeleteDTO();
            userCacheAsyncDeleteDTO.setCode(CacheAsyncDeleteCode.USER_INFO_DELETE.getCode());
            Map<String,Object> jsonParam = new HashMap<>();
            jsonParam.put("userId",userDTO.getUserId());
            userCacheAsyncDeleteDTO.setJson(JSON.toJSONString(jsonParam));
            Message message = new Message();
            message.setTopic(UserProviderTopicNames.CACHE_ASYNC_DELETE_TOPIC);
            message.setBody(JSON.toJSONString(userCacheAsyncDeleteDTO).getBytes());
            //延迟一秒进行缓存的二次删除
            message.setDelayTimeLevel(1);
            try {
                mqProducer.send(message);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }

    @Override
    public boolean insertUserInfo(UserDTO userDTO) {
        int insert = userMapper.insert(ConvertBeanUtils.convert(userDTO, UserPO.class));
        return insert>=0;
    }

    @Override
    public Map<Long, UserDTO> batchQueryUserInfo(List<Long> userIdList) {
        if(CollectionUtils.isEmpty(userIdList)){
            return Maps.newHashMap();
        }
        final List<String> keyList=new ArrayList<>();
        userIdList.forEach(userId->{
            keyList.add(userProviderCacheKeyBuilder.buildUserInfoKey(userId));
        });
        List<UserDTO> userDTOS = redisTemplate.opsForValue().multiGet(keyList).stream().filter(x->x!=null).collect(Collectors.toList());
        if(userDTOS!=null&&userDTOS.size()==userIdList.size()){
            return userDTOS.stream().collect(Collectors.toMap(UserDTO::getUserId,x->x));
        }

        List<Long> userIdInCacheList=userDTOS.stream().map(UserDTO::getUserId).collect(Collectors.toList());
        List<Long> userIdNotInCacheList=userIdList.stream().filter(userId->!userIdInCacheList.contains(userId)).collect(Collectors.toList());

        //多綫程查詢 替換union all
        Map<Long, List<Long>> userIdMap=userIdNotInCacheList.stream().collect(Collectors.groupingBy(userId->userId%100));
        List<UserDTO> dbQueryResult=new CopyOnWriteArrayList<>();
        userIdMap.values().parallelStream().forEach(queryUserIdList->{
            dbQueryResult.addAll(ConvertBeanUtils.convertList(userMapper.selectBatchIds(queryUserIdList),UserDTO.class));
        });
        if(!CollectionUtils.isEmpty(dbQueryResult)) {
            Map<String, UserDTO> saveCacheMap = dbQueryResult.stream().collect(Collectors.toMap(userDto -> userProviderCacheKeyBuilder.buildUserInfoKey(userDto.getUserId()), x -> x));
            redisTemplate.opsForValue().multiSet(saveCacheMap);
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                    for (String redisKey: saveCacheMap.keySet()) {
                        operations.expire((K) redisKey, StringUtils.createRandomTime(),TimeUnit.MINUTES);
                    }
                    return null;
                }
            });
            userDTOS.addAll(dbQueryResult);
        }
        return userDTOS.stream().collect(Collectors.toMap(UserDTO::getUserId,x->x));
    }
}
