-- CHAIWALA is renamed BREWBERRY (Brewberry Beverages Ltd.): the old name carried connotations a demo
-- shouldn't have. The market keeps its place in the list, so its prices are unchanged; every service that
-- stores the symbol renames it in the same release.

UPDATE settlements SET obligation = replace(obligation, 'CHAIWALA', 'BREWBERRY') WHERE obligation LIKE '%CHAIWALA%';
