-- VAT breakdown on payment collections. Prices are treated as VAT-INCLUSIVE (the Ugandan norm):
-- vat = amount × r/(100+r); net = amount − vat. Zero rate (the default) leaves both null — tax
-- reporting is opt-in per deployment, never silently invented.
alter table payment add column vat_amount numeric(19,2);
alter table payment add column net_amount numeric(19,2);
