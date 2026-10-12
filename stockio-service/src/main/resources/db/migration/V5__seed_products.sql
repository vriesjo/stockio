-- SKUs are stable slugs derived from titles because the source list has no SKU values.
INSERT INTO products (
    sku, title, description, status, category, price_cents, currency, attributes
)
VALUES
    ('OZEKI-SAKE-DRY', 'Ozeki Sake Dry', 'Flesje Sake', 'ACTIVE', 'H', 295, 'EUR', '{"content":"180","unit":"ml"}'::jsonb),
    ('KIRIN-ICHIBAN', 'Kirin Ichiban', 'Japans bier', 'ACTIVE', 'H', 195, 'EUR', '{"content":"330","unit":"ml"}'::jsonb),
    ('MANGAJO-GRANAAT', 'Mangajo Granaat', 'Flesje vruchtensap', 'ACTIVE', 'H', 230, 'EUR', '{"content":"250","unit":"ml"}'::jsonb),
    ('MANGAJO-ORANJE', 'Mangajo Oranje', 'Flesje vruchtensap', 'ACTIVE', 'H', 230, 'EUR', '{"content":"250","unit":"ml"}'::jsonb),
    ('MANGAJO-LEM', 'Mangajo Lem', 'Flesje vruchtensap', 'ACTIVE', 'H', 230, 'EUR', '{"content":"250","unit":"ml"}'::jsonb),
    ('SUSHIGARI', 'Sushigari', 'Potje gember', 'ACTIVE', 'H', 395, 'EUR', '{"content":"140","unit":"ml"}'::jsonb),
    ('MAKI-KOMKOMMER', 'Maki Komkommer', NULL, 'ACTIVE', 'V', 275, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('MAKI-AVOCADO', 'Maki Avocado', NULL, 'ACTIVE', 'V', 350, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('MAKI-ZALM', 'Maki Zalm', NULL, 'ACTIVE', 'V', 475, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('MAKI-TONIJN', 'Maki Tonijn', NULL, 'ACTIVE', 'V', 495, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('MAKI-CALIFORNIA', 'Maki California', NULL, 'ACTIVE', 'V', 395, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('NIGIRI-ZALM-6', 'Nigiri Zalm 6', NULL, 'ACTIVE', 'V', 850, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('NIGIRI-TONIJN-6', 'Nigiri Tonijn 6', NULL, 'ACTIVE', 'V', 1050, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('NIGIRI-DUO-6', 'Nigiri Duo 6', NULL, 'ACTIVE', 'V', 950, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('SASHIMI-ZALM-8', 'Sashimi Zalm 8', NULL, 'ACTIVE', 'V', 650, 'EUR', '{"content":"150","unit":"gr"}'::jsonb),
    ('SASHIMI-DUO-8', 'Sashimi Duo 8', NULL, 'ACTIVE', 'V', 775, 'EUR', '{"content":"150","unit":"gr"}'::jsonb),
    ('SPICY-ROLL-CALIFORNIA', 'Spicy Roll California', NULL, 'ACTIVE', 'V', 995, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('SPICY-ROLL-VEGGIE', 'Spicy Roll Veggie', NULL, 'ACTIVE', 'V', 1095, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('SPICY-ROLL-CHICKEN', 'Spicy Roll Chicken', NULL, 'ACTIVE', 'V', 775, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('SPICY-ROLL-ZALM', 'Spicy Roll Zalm', NULL, 'ACTIVE', 'V', 795, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('SPICY-ROLL-TONIJN', 'Spicy Roll Tonijn', NULL, 'ACTIVE', 'V', 895, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('VEGGIE-ROLL', 'Veggie Roll', NULL, 'ACTIVE', 'V', 575, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('CALI-ROLL', 'Cali Roll', NULL, 'ACTIVE', 'V', 695, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('ZALM-ROLL', 'Zalm Roll', NULL, 'ACTIVE', 'V', 795, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('CHICKEN-SESAM-ROLL', 'Chicken Sesam Roll', NULL, 'ACTIVE', 'V', 695, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('TEMPURA-ROLL', 'Tempura Roll', NULL, 'ACTIVE', 'V', 695, 'EUR', '{"content":"175","unit":"gr"}'::jsonb),
    ('CRUNCHY-CHICKEN-ROLL', 'Crunchy Chicken Roll', NULL, 'ACTIVE', 'V', 795, 'EUR', '{"content":"200","unit":"gr"}'::jsonb),
    ('CRUNCHY-ZALM-ROLL', 'Crunchy Zalm Roll', NULL, 'ACTIVE', 'V', 895, 'EUR', '{"content":"200","unit":"gr"}'::jsonb),
    ('CRUNCHY-CALI-ROLL', 'Crunchy Cali Roll', NULL, 'ACTIVE', 'V', 795, 'EUR', '{"content":"200","unit":"gr"}'::jsonb),
    ('CRUNCHY-TEMPURA', 'Crunchy Tempura', NULL, 'ACTIVE', 'V', 795, 'EUR', '{"content":"200","unit":"gr"}'::jsonb),
    ('CRUNCHY-TONIJN-ROLL', 'Crunchy Tonijn Roll', NULL, 'ACTIVE', 'V', 995, 'EUR', '{"content":"200","unit":"gr"}'::jsonb),
    ('EDAMAME', 'Edamame', NULL, 'ACTIVE', 'V', 295, 'EUR', '{"content":"100","unit":"gr"}'::jsonb)
ON CONFLICT (sku) DO NOTHING;
