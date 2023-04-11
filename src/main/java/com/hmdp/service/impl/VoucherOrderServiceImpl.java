package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author steven
 * @since 2023-04-02
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("orderId");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),
                String.valueOf(orderId)
        );
        //2.判断结果是否为0
        int r = result.intValue();
        if (r != 0){
            //2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "用户已下单");
        }
        // 3.订单信息已经放入基于Stream消息队列中

        // 4.另开线程去处理订单信息
        // 4.2.4 在主线程获取代理对象并注入
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4.返回订单id
        return Result.ok(orderId);
    }

    //创建单线程化线程池，用来运行实现Runnable的类
    private static final ExecutorService EXECUTORSERVICE = Executors.newSingleThreadExecutor();

    //等依赖加载完再全部执行
    @PostConstruct
    private void init(){
        EXECUTORSERVICE.submit(new VoucherOrderHandler());
    }

    //实现Runnable接口
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                //try中出现异常，在ACK确认的过程中出现异常，就是未确认，将把该消息放入pending-list中重新ACK
                try {
                    //根据控制报错，队列不存在，就先创建
                    initStreamQueue();
                    //1.获取消息队列中的订单信息 XREADGRROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        //2.1 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //2.2 如果获取成功，可以下单
//                    handleVoucherOrder(voucherOrder);
                    proxy.asyncCreateVoucherOrder(voucherOrder);
                    //4.ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());
                } catch (Exception e) {
                    log.error("处理订单异常"+e.getMessage());
                    //处理pending-list的数据
                    handlePendingList();
                }
            }
        }

        //创建消息队列名称与组名称，不然会报错
        private void initStreamQueue(){
            Boolean exists = stringRedisTemplate.hasKey(queueName);
            if (BooleanUtil.isFalse(exists)){
                log.info("基于stream消息队列名称不存在，开始创建消息队列名称："+queueName);
                //不存在，需要创建
                stringRedisTemplate.opsForStream().createGroup(queueName,ReadOffset.latest(),"g1");
                log.info("队列名称:" + queueName + "与group:g1创建完毕");
                return;
            }
            //stream存在，判断group是否存在
            StreamInfo.XInfoGroups groups = stringRedisTemplate.opsForStream().groups(queueName);
            if (groups.isEmpty()){
                log.info("group不存在，开始创建group");
                //group不存在，开始创建group
                stringRedisTemplate.opsForStream().createGroup(queueName, ReadOffset.latest(), "g1");
                log.info("group创建完毕");
            }
        }

        //出现异常的消息可能未被确认，就会被放到pending-list中，所以需在pending-list二次处理
        private void handlePendingList() {
            while (true){
                try {
                    //1.获取pending-list中的订单信息 XREADGRROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    // 0 代表从第一条开始读
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        //2.1 如果获取失败，说明pending-list没有消息，结束循环
                        break;
                    }
                    //3.解析消息中的订单信息
                    //解析消息，封装成VoucherOrder信息，进行下单
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //2.2 如果获取成功，可以下单
                    proxy.asyncCreateVoucherOrder(voucherOrder);
                    //4.ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());
                } catch (Exception e) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    @Override
    @Transactional
    public void asyncCreateVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            // 用户已经购买过
            log.error("用户已经购买过一次！");
            return;
        }

        // 5、扣减库存(使用乐观锁思想，修改时判断是否被修改过)
        boolean isSuccess = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        if(!isSuccess){
            log.error("库存不足，抢购失败!");
            return;
        }
        save(voucherOrder);
    }
}
