package com.leyou.seckill.controller;


import com.leyou.auth.entity.UserInfo;
import com.leyou.response.Result;
import com.leyou.seckill.access.AccessLimit;
import com.leyou.seckill.interceptor.LoginInterceptor;
import com.leyou.seckill.service.SeckillService;

import com.leyou.seckill.vo.SeckillGoods;
import com.leyou.seckill.vo.SeckillMessage;
import com.leyou.seckill.vo.SeckillParameter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * @Author: 98050
 * @Time: 2018-11-10 16:57
 * @Feature:
 */
@RestController
@RequestMapping
public class SeckillController implements InitializingBean {

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "leyou:seckill:stock";

    private Map<Long,Boolean> localOverMap = new HashMap<>();

    /**
     * 系统初始化，初始化秒杀商品数量
     * @throws Exception
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        //1.查询可以秒杀的商品
        List<SeckillGoods> seckillGoods = this.seckillService.querySeckillGoods();
        if (seckillGoods == null || seckillGoods.size() == 0){
            return;
        }
        BoundHashOperations<String,Object,Object> hashOperations = this.stringRedisTemplate.boundHashOps(KEY_PREFIX);
        if (hashOperations.hasKey(KEY_PREFIX)){
            hashOperations.delete(KEY_PREFIX);
        }
        seckillGoods.forEach(goods -> {
            hashOperations.put(goods.getSkuId().toString(),goods.getStock().toString());
            localOverMap.put(goods.getSkuId(),false);
        });
    }

    /**
     * 添加秒杀商品(后台)
     * @param seckillParameter
     * @return
     */
    @PostMapping("addSeckill")
    public ResponseEntity<Boolean> addSeckillGoods(@RequestBody SeckillParameter seckillParameter){
        if (seckillParameter != null){
            this.seckillService.addSeckillGoods(seckillParameter);
        }else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.ok().build();
    }

    /**
     * 查询秒杀商品
     * @return
     */
    @GetMapping("list")
    public ResponseEntity<List<SeckillGoods>> querySeckillGoods(){
        List<SeckillGoods> list = this.seckillService.querySeckillGoods();
        if (list == null || list.size() < 0){
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(list);
    }


    @PostMapping("/{path}/seck")
    public ResponseEntity<String> seckillOrder(@PathVariable("path") String path, @RequestBody SeckillGoods seckillGoods){

        String result = "排队中";

        UserInfo userInfo = LoginInterceptor.getLoginUser();

        //1.验证路径
        boolean check = this.seckillService.checkSeckillPath(seckillGoods.getId(),userInfo.getId(),path);
        if (!check){
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        //2.内存标记，减少redis访问
        boolean over = localOverMap.get(seckillGoods.getSkuId());
        if (over){
            return ResponseEntity.ok(result);
        }

        //3.读取库存，减一后更新缓存
        BoundHashOperations<String,Object,Object> hashOperations = this.stringRedisTemplate.boundHashOps(KEY_PREFIX);
        String s = (String) hashOperations.get(seckillGoods.getSkuId().toString());
        if (s == null){
            return ResponseEntity.ok(result);
        }
        int stock = Integer.valueOf(s) - 1;

        //4.库存不足直接返回
        if (stock < 0){
            localOverMap.put(seckillGoods.getSkuId(),true);
            return ResponseEntity.ok(result);
        }
        //5.更新库存
        hashOperations.delete(seckillGoods.getSkuId().toString());
        hashOperations.put(seckillGoods.getSkuId().toString(),String.valueOf(stock));

        //6.库存充足，请求入队
        //6.1 获取用户信息
        SeckillMessage seckillMessage = new SeckillMessage(userInfo,seckillGoods);
        //6.2 发送消息
        this.seckillService.sendMessage(seckillMessage);


        return ResponseEntity.ok(result);
    }

    /**
     * 根据userId查询订单号
     * @param userId
     * @return
     */
    @GetMapping("orderId")
    public ResponseEntity<Long> checkSeckillOrder(Long userId){
        Long result = this.seckillService.checkSeckillOrder(userId);
        if (result == null){
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(result);

    }

    /**
     * 创建秒杀路径
     * @param goodsId
     * @return
     */
    @AccessLimit(seconds = 20,maxCount = 5,needLogin = true)
    @GetMapping("get_path/{goodsId}")
    public ResponseEntity<String> getSeckillPath(@PathVariable("goodsId") Long goodsId){
        UserInfo userInfo = LoginInterceptor.getLoginUser();
        if (userInfo == null){
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String str = this.seckillService.createPath(goodsId,userInfo.getId());
        if (StringUtils.isEmpty(str)){
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(str);
    }

}
