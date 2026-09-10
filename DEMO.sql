create table users (id int32, name string, age int32, score float64);
insert into users values (1, 'alice', 23, 95.5);
insert into users values (2, 'bob', 30, 88.0);
insert into users values (3, 'carol', 22, 91.5);
show tables;
select id, name from users where age > 24 order by score desc;

create table orders (oid int32, uid int32, amount float64);
insert into orders values (10, 1, 100.0);
insert into orders values (11, 2, 50.0);
insert into orders values (12, 1, 75.0);
select u.name, o.amount from users u join orders o on u.id = o.uid order by o.amount;
select u.id, o.oid from users u left join orders o on u.id = o.uid order by u.id;
select u.name, count(*), avg(o.amount) from users u join orders o on u.id = o.uid group by u.name order by u.name;

select id, name from users where age > 22 order by id;
create index idx_age on users(age);
show indexes;
select id, name from users where age > 22 order by id;
select id, name from users where age = 30;
insert into users values (4, 'dave', 30, 99.0);
select id, name from users where age = 30 order by id;
