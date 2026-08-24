-- =====================================================================
-- 美食主题演示数据（可选，仅 INSERT，不修改任何表结构）
-- 适用库: information（与 application.yaml 数据源保持一致后再执行）
-- 说明: 全部使用 INSERT IGNORE，可重复执行；不会覆盖已有数据
-- 账号: 13800000001 / 13800000002 / 13800000003 密码均为 123456
--       （tb_user.password 存明文，与 UserMapper.selectUserByPhoneAndPassword
--        的明文比对 SQL 保持一致，方便演示密码登录）
-- =====================================================================
USE information;

-- 店铺分类
INSERT IGNORE INTO tb_shop_type (id, name, icon, sort) VALUES
(1, '火锅', 'hotpot.svg', 1),
(2, '烧烤', 'bbq.svg', 2),
(3, '日料', 'sushi.svg', 3),
(4, '川菜', 'chuan.svg', 4),
(5, '粤菜', 'dimsum.svg', 5),
(6, '奶茶', 'tea.svg', 6),
(7, '咖啡', 'coffee.svg', 7),
(8, '甜品', 'dessert.svg', 8),
(9, '面馆', 'noodles.svg', 9);

-- 美食店铺（images 指向前端 imgs/food/ 下的资源，需随前端一起拷贝到 nginx html）
INSERT IGNORE INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours, create_time, update_time) VALUES
(1, '老码头重庆火锅', 1, '/imgs/food/shop-hotpot.svg,/imgs/food/shop-1.svg', '西湖区', '文三路100号', 120.151, 30.273, 9800, 12000, 2100, 46, '10:00-23:00', NOW(), NOW()),
(2, '炭火兄弟烧烤', 2, '/imgs/food/shop-bbq.svg,/imgs/food/shop-2.svg', '拱墅区', '湖墅南路88号', 120.147, 30.298, 6500, 9800, 1560, 45, '16:00-02:00', NOW(), NOW()),
(3, '樱和日料食堂', 3, '/imgs/food/shop-sushi.svg,/imgs/food/shop-3.svg', '上城区', '延安路200号', 120.161, 30.242, 15800, 4200, 880, 48, '11:00-22:00', NOW(), NOW()),
(4, '川香里·江湖菜', 4, '/imgs/food/shop-chuan.svg,/imgs/food/shop-4.svg', '西湖区', '古墩路599号', 120.116, 30.285, 7200, 7600, 1340, 46, '10:30-21:30', NOW(), NOW()),
(5, '广式茶点轩', 5, '/imgs/food/shop-dimsum.svg,/imgs/food/shop-5.svg', '上城区', '庆春路260号', 120.172, 30.252, 8800, 6100, 1120, 47, '08:00-20:30', NOW(), NOW()),
(6, '茶言观色奶茶', 6, '/imgs/food/shop-tea.svg,/imgs/food/shop-6.svg', '西湖区', '文二路210号', 120.138, 30.276, 1800, 26000, 3900, 44, '09:00-22:00', NOW(), NOW()),
(7, '慢时光咖啡', 7, '/imgs/food/shop-coffee.svg,/imgs/food/shop-7.svg', '滨江区', '江陵路1800号', 120.214, 30.208, 3200, 8600, 1730, 45, '08:30-22:00', NOW(), NOW()),
(8, '甜心烘焙坊', 8, '/imgs/food/shop-dessert.svg,/imgs/food/shop-8.svg', '拱墅区', '大关路105号', 120.152, 30.305, 2800, 15000, 2600, 46, '09:30-21:30', NOW(), NOW()),
(9, '巷子口小面馆', 9, '/imgs/food/shop-noodles.svg,/imgs/food/shop-9.svg', '上城区', '中山中路88号', 120.168, 30.238, 1500, 19000, 3300, 43, '07:00-20:00', NOW(), NOW()),
(10, '潮汕牛肉火锅', 1, '/imgs/food/shop-beef.svg,/imgs/food/shop-10.svg', '西湖区', '文三路370号', 120.146, 30.279, 10500, 5300, 980, 47, '11:00-23:00', NOW(), NOW());

-- 优惠券（type: 0普通券 1秒杀券；金额单位分）
INSERT IGNORE INTO tb_voucher (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status, create_time, update_time) VALUES
(1, 1, '50元代100元火锅券', '周末节假日通用', '每桌限用2张，酒水除外', 5000, 10000, 1, 1, NOW(), NOW()),
(2, 1, '满100减30代金券', '午市晚市通用', '不与店内其他优惠同享', 3000, 3000, 0, 1, NOW(), NOW()),
(3, 2, '8.8元抢50元烧烤代金券', '深夜食堂专享', '每单限用1张', 880, 5000, 1, 1, NOW(), NOW()),
(4, 3, '99元双人日料套餐券', '需提前1天预约', '含前菜/主食/甜品各2份', 9900, 15800, 1, 1, NOW(), NOW()),
(5, 4, '满80减20代金券', '全天可用', '每桌限用1张', 2000, 2000, 0, 1, NOW(), NOW()),
(6, 5, '9.9元下午茶点心券', '仅限14:00-17:00', '任选2款点心', 990, 2000, 0, 1, NOW(), NOW()),
(7, 6, '第二杯半价券', '人气奶茶通用', '每次限购1张', 100, 500, 0, 1, NOW(), NOW()),
(8, 7, '6.6元美式咖啡券', '仅限工作日', '含中杯美式1杯', 660, 1800, 1, 1, NOW(), NOW()),
(9, 8, '满50减15甜品券', '全场通用', '生日蛋糕除外', 1500, 1500, 0, 1, NOW(), NOW()),
(10, 10, '68元代100元牛肉火锅券', '周一至周五可用', '每桌限用1张', 6800, 10000, 1, 1, NOW(), NOW());

-- 秒杀券（与异步秒杀联动，应用启动时会自动把库存预热到 Redis seckill:stock:{id}）
INSERT IGNORE INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time, create_time, update_time) VALUES
(1, 200, '2026-08-15 00:00:00', '2026-08-31 23:59:59', NOW(), NOW()),
(3, 100, '2026-08-15 00:00:00', '2026-08-31 23:59:59', NOW(), NOW()),
(4, 80,  '2026-08-15 00:00:00', '2026-08-31 23:59:59', NOW(), NOW()),
(8, 150, '2026-08-15 00:00:00', '2026-08-31 23:59:59', NOW(), NOW()),
(10, 120,'2026-08-15 00:00:00', '2026-08-31 23:59:59', NOW(), NOW());

-- 演示用户（密码 123456，明文存储便于密码登录演示）
INSERT IGNORE INTO tb_user (id, phone, password, nick_name, icon, create_time, update_time) VALUES
(1, '13800000001', '123456', '美食达人小王', '/imgs/food/avatar-1.svg', NOW(), NOW()),
(2, '13800000002', '123456', '探店小分队队长', '/imgs/food/avatar-2.svg', NOW(), NOW()),
(3, '13800000003', '123456', '干饭魂', '/imgs/food/avatar-3.svg', NOW(), NOW());

INSERT IGNORE INTO tb_user_info (user_id, city, introduce, fans, followee, gender, birthday, credits, level, create_time, update_time) VALUES
(1, '杭州', '资深吃货，专注火锅与甜品探店', 320, 58, 1, '1998-05-20', 1200, 2, NOW(), NOW()),
(2, '杭州', '每周探店2-3家，跟着我吃不踩雷', 210, 120, 1, '2000-11-11', 860, 1, NOW(), NOW()),
(3, '杭州', '干饭魂不灭，性价比之王', 88, 200, 0, '1996-03-15', 500, 1, NOW(), NOW());

-- 探店笔记（美食主题）
INSERT IGNORE INTO tb_blog (id, shop_id, user_id, title, images, content, liked, comments, create_time, update_time) VALUES
(1, 1, 1, '这家重庆老火锅，锅底香到犯规', '/imgs/food/blog-1.svg,/imgs/food/blog-1b.svg', '牛油锅底一上桌就香得走不动道，毛肚七上八下刚刚好，麻辣鲜香很地道，人均不到100，性价比超高！', 326, 45, NOW(), NOW()),
(2, 6, 2, '杭州奶茶地图，这家的茶香最正', '/imgs/food/blog-2.svg', '奶盖绵密、茶底清爽，少糖也不涩，绝对是奶茶爱好者的宝藏小店，建议避开晚高峰。', 218, 32, NOW(), NOW()),
(3, 2, 3, '深夜烧烤摊，炭火味才是灵魂', '/imgs/food/blog-3.svg', '晚上10点开始排队，羊肉串肥瘦相间，配上冰啤酒绝了。老板烤得慢但值得等！', 156, 21, NOW(), NOW()),
(4, 3, 1, '人均150的日料食堂，性价比拉满', '/imgs/food/blog-4.svg', '刺身新鲜、寿司师傅手艺在线，午市套餐更划算，建议提前预约靠窗位。', 142, 18, NOW(), NOW()),
(5, 7, 2, '咖啡续命选手的宝藏小店', '/imgs/food/blog-5.svg', '手冲豆单很丰富，燕麦拿铁入口顺滑，店面安静适合办公看书。', 98, 12, NOW(), NOW()),
(6, 8, 3, '这家甜品店的提拉米苏绝了', '/imgs/food/blog-6.svg', '酒香和咖啡香平衡得很好，甜而不腻，下午茶必点，草莓千层也很惊艳。', 88, 9, NOW(), NOW());

-- 关注关系（用户1关注2、3；用户2关注1）
INSERT IGNORE INTO tb_follow (id, user_id, follow_user_id, create_time) VALUES
(1, 1, 2, NOW()),
(2, 1, 3, NOW()),
(3, 2, 1, NOW());