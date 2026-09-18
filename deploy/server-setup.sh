#!/bin/bash
#
# 服务器初始化：建库、建账号、建运行用户和目录。
#
# 幂等，可以反复跑。密码从 /etc/pet-server/env 读，所以先写那个文件。
#
# 用法：sudo bash server-setup.sh
#
# 关于两个 host 的必要性：MySQL 默认会做反向域名解析，127.0.0.1 会被解析成
# localhost，于是连接匹配的是 'pet_app'@'localhost' 而不是 'pet_app'@'127.0.0.1'。
# 只建其中一个的话，另一个必定 Access denied —— 报错里显示的 host 和你配置的
# 不一样，很容易看懵。两个都建最省事。

set -euo pipefail

ENV_FILE=/etc/pet-server/env
APP_USER=petapp
APP_DIR=/opt/pet
DB_NAME=virtual_pet

if [ "$(id -u)" -ne 0 ]; then
    echo "需要 root 权限" >&2
    exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
    echo "缺少 $ENV_FILE，请先写下面两行再跑本脚本：" >&2
    echo "  PET_DB_PASSWORD=<数据库密码>" >&2
    echo "  PET_ALLOWED_ORIGINS=http://<公网IP>:8000" >&2
    exit 1
fi

DBPASS=$(grep '^PET_DB_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)
if [ -z "$DBPASS" ]; then
    echo "$ENV_FILE 里没有 PET_DB_PASSWORD" >&2
    exit 1
fi

echo "==> 建库与账号"
mysql <<SQL
CREATE DATABASE IF NOT EXISTS ${DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'pet_app'@'localhost' IDENTIFIED BY '${DBPASS}';
CREATE USER IF NOT EXISTS 'pet_app'@'127.0.0.1' IDENTIFIED BY '${DBPASS}';
-- 显式 ALTER 一遍：账号可能已经存在但密码是旧的，CREATE IF NOT EXISTS 不会改密码
ALTER USER 'pet_app'@'localhost' IDENTIFIED BY '${DBPASS}';
ALTER USER 'pet_app'@'127.0.0.1' IDENTIFIED BY '${DBPASS}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES
    ON ${DB_NAME}.* TO 'pet_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES
    ON ${DB_NAME}.* TO 'pet_app'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

echo "    账号：$(mysql -N -e "SELECT GROUP_CONCAT(CONCAT(user,'@',host)) FROM mysql.user WHERE user='pet_app'")"
echo "    连接自检：$(mysql -h 127.0.0.1 -u pet_app -p"${DBPASS}" ${DB_NAME} -N -e 'SELECT VERSION()' 2>/dev/null || echo '失败')"

echo "==> 建运行用户与目录"
if ! id "$APP_USER" >/dev/null 2>&1; then
    useradd --system --no-create-home --shell /usr/sbin/nologin "$APP_USER"
    echo "    已创建用户 $APP_USER"
else
    echo "    用户 $APP_USER 已存在"
fi

mkdir -p "$APP_DIR"
mkdir -p /etc/pet-server
chown root:"$APP_USER" "$ENV_FILE"
chmod 640 "$ENV_FILE"

echo "==> 完成"
echo "    库：$DB_NAME    运行用户：$APP_USER    目录：$APP_DIR"
