
/Users/vriesjo/Development/Home/itzitzo/itzitzo-mcp/stockio-service/src/main/resources/db/migration/V4__create_product_table.sql

Mapping:
title -> title
description -> description
status -> status = ACTIVE
content, unit -> attributes : {"content": "180", "unit": "ml"}
sale_price -> price_cents (sale_price * 100)
category -> category
currency: EUR

title	| description	| content	| unit	| sale_price	| category
Ozeki Sake Dry	| Flesje Sake |	180	 | ml	| € 2.95 |	H (houdbaar)
Kirin Ichiban	| Japans bier |	330	 | ml	| € 1.95 |	H
Mangajo Granaat	| Flesje vruchtensap |	250	 | ml	| € 2.30 |	H
Mangajo Oranje	| Flesje vruchtensap |	250	 | ml	| € 2.30 |	H
Mangajo Lem	| Flesje vruchtensap |	250	 | ml	| € 2.30 |	H
Sushigari	| Potje gember |	140	 | ml	| € 3.95 |	H
Maki Komkommer	| 	175	| gr	| € 2.75 |	V (vers)
Maki Avocado	| 	175	| gr	| € 3.50 |	V
Maki Zalm	| 	175	| gr	| € 4.75 |	V
Maki Tonijn	| 	175	| gr	| € 4.95 |	V
Maki California	| 	175	| gr	| € 3.95 |	V
Nigiri Zalm 6	| 	175	| gr	| € 8.50 |	V
Nigiri Tonijn 6	| 	175	| gr	| € 10.50 |	V
Nigiri Duo 6	| 	175	| gr	| € 9.50 |	V
Sashimi Zalm 8	| 	150	| gr	| € 6.50 |	V
Sashimi Duo 8	| 	150	| gr	| € 7.75 |	V
Spicy Roll California	| 	175	| gr	| € 9.95 |	V
Spicy Roll Veggie	| 	175	| gr	| € 10.95 |	V
Spicy Roll Chicken	| 	175	| gr	| € 7.75 |	V
Spicy Roll Zalm	| 	175	| gr	| € 7.95 |	V
Spicy Roll Tonijn	| 	175	| gr	| € 8.95 |	V
Veggie Roll	| 	175	| gr	| € 5.75 |	V
Cali Roll	| 	175	| gr	| € 6.95 |	V
Zalm Roll	| 	175	| gr	| € 7.95 |	V
Chicken Sesam Roll	| 	175	| gr	| € 6.95 |	V
Tempura Roll	| 	175	| gr	| € 6.95 |	V
Crunchy Chicken Roll	| 	200	| gr	| € 7.95 |	V
Crunchy Zalm Roll	| 	200	| gr	| € 8.95 |	V
Crunchy Cali Roll	| 	200	| gr	| € 7.95 |	V
Crunchy Tempura	| 	200	| gr	| € 7.95 |	V
Crunchy Tonijn Roll	| 	200	| gr	| € 9.95 |	V
Edamame	| 	100	| gr	| € 2.95 |	V
